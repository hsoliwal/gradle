/*
 * Copyright 2026 Gradle and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.tasks.compile;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.ClientCodeWrapper;
import com.sun.tools.javac.util.Context;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import javax.annotation.processing.Processor;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/** Per-compilation provider admission and resource ownership; default javac never enters it. */
@NullMarked
final class M3JavacASTTask implements Closeable {
    private static final String PROVIDER_OPTION = "-XDm3AST=";
    private static final String PATH_OPTION = "-XDm3ASTPath=";

    private final URLClassLoader loader;
    private final M3JavacASTProvider provider;
    private final List<String> options;
    private final List<Diagnostic<? extends JavaFileObject>> diagnostics = new ArrayList<>();
    private M3JavacASTProvider.@Nullable M3Compilation compilation;
    private boolean closed;

    private M3JavacASTTask(URLClassLoader loader, M3JavacASTProvider provider, List<String> options) {
        this.loader = loader;
        this.provider = provider;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
    }

    static boolean requested(List<String> options) {
        for (String option : options) {
            if (option.startsWith("-XDm3AST")) {
                return true;
            }
        }
        return false;
    }

    static M3JavacASTTask open(List<String> options) {
        if (!"21".equals(System.getProperty("java.specification.version"))) {
            throw new IllegalArgumentException("M3 AST integration requires a Java 21 compiler worker");
        }
        String providerName = singleOption(options, PROVIDER_OPTION);
        String path = singleOption(options, PATH_OPTION);
        if (!SourceVersion.isName(providerName) || path.isEmpty()) {
            throw new IllegalArgumentException("M3 AST requires an exact provider class and nonempty provider classpath");
        }
        List<URL> urls = new ArrayList<>();
        try {
            for (String entry : path.split(Pattern.quote(File.pathSeparator), -1)) {
                File file = new File(entry);
                if (entry.isEmpty() || !(file.isFile() || file.isDirectory())) {
                    throw new IllegalArgumentException("M3 AST provider classpath entry is missing: " + entry);
                }
                urls.add(file.toURI().toURL());
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot resolve M3 AST provider classpath", failure);
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), M3JavacASTProvider.class.getClassLoader());
        try {
            // Select before initialization. No unrelated provider constructor is invoked.
            Class<? extends M3JavacASTProvider> type = Class.forName(providerName, false, loader).asSubclass(M3JavacASTProvider.class);
            if (type.getClassLoader() != loader) {
                throw new IllegalArgumentException("Selected M3 AST provider is outside the explicit provider assets: " + providerName);
            }
            M3JavacASTProvider provider = type.getConstructor().newInstance();
            return new M3JavacASTTask(loader, provider, options);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            try {
                loader.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            Throwable cause = failure instanceof InvocationTargetException ? ((InvocationTargetException) failure).getTargetException() : failure;
            throw new IllegalArgumentException("Selected M3 AST provider is missing, unsupported or failed to initialize: " + providerName, cause);
        }
    }

    DiagnosticListener<JavaFileObject> listener(DiagnosticListener<? super JavaFileObject> original, Context context) {
        return new M3DiagnosticListener(original, ClientCodeWrapper.instance(context));
    }

    /** Preserve Gradle's trusted rich formatter and javac's existing client/source-unwrapping policy. */
    @ClientCodeWrapper.Trusted
    private final class M3DiagnosticListener implements DiagnosticListener<JavaFileObject> {
        private final DiagnosticListener<? super JavaFileObject> original;
        private final DiagnosticListener<? super JavaFileObject> outward;
        private final ClientCodeWrapper wrapper;

        private M3DiagnosticListener(DiagnosticListener<? super JavaFileObject> original, ClientCodeWrapper wrapper) {
            this.original = original;
            this.wrapper = wrapper;
            this.outward = wrapper.wrap(original);
        }

        @Override
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            // Let this same javac Context unwrap its own file-object wrappers. The
            // collector is intentionally ordinary client code and performs no callback.
            DiagnosticCollector<JavaFileObject> sources = new DiagnosticCollector<>();
            wrapper.wrap(sources).report(diagnostic);
            Diagnostic<? extends JavaFileObject> sourceDiagnostic = sources.getDiagnostics().get(0);
            diagnostics.add(sourceDiagnostic);
            Diagnostic<? extends JavaFileObject> originalDiagnostic = compilation == null
                ? sourceDiagnostic : compilation.originalDiagnostic(sourceDiagnostic);
            if (original instanceof DiagnosticToProblemListener && original == outward) {
                // The formatter requires the original JCDiagnostic, not a generic
                // Diagnostic view. Only source locations use the provider projection.
                ((DiagnosticToProblemListener) original).report(diagnostic, originalDiagnostic);
            } else {
                // Preserve the original listener's own trusted/untrusted boundary.
                outward.report(originalDiagnostic);
            }
        }
    }

    Iterable<? extends JavaFileObject> prepare(Iterable<? extends JavaFileObject> sources, Charset encoding, StandardJavaFileManager manager) {
        if (compilation != null || closed) {
            throw new IllegalStateException("M3 AST compilation has already been prepared or closed");
        }
        compilation = Objects.requireNonNull(provider.prepare(sources, encoding, options, manager,
            () -> Collections.unmodifiableList(new ArrayList<>(diagnostics))), "M3 AST provider returned no compilation");
        return Objects.requireNonNull(compilation.sources(), "M3 AST provider returned no compiler inputs");
    }

    JavaFileManager fileManager(JavaFileManager original) {
        return Objects.requireNonNull(Objects.requireNonNull(compilation, "M3 AST provider is not prepared")
            .fileManager(original), "M3 AST provider returned no file manager");
    }

    JavaCompiler.CompilationTask attach(JavacTask task, IntSupplier errorCount) {
        return Objects.requireNonNull(Objects.requireNonNull(compilation, "M3 AST provider is not prepared")
            .attach(task, errorCount), "M3 AST provider returned no execution task");
    }

    JavaCompiler.CompilationTask closeAfter(JavaCompiler.CompilationTask delegate) {
        return new JavaCompiler.CompilationTask() {
            @Override
            public void setProcessors(Iterable<? extends Processor> processors) {
                delegate.setProcessors(processors);
            }

            @Override
            public void setLocale(Locale locale) {
                delegate.setLocale(locale);
            }

            @Override
            public void addModules(Iterable<String> moduleNames) {
                delegate.addModules(moduleNames);
            }

            @Override
            public Boolean call() {
                try (M3JavacASTTask ignored = M3JavacASTTask.this) {
                    return delegate.call();
                } catch (IOException failure) {
                    throw new UncheckedIOException("Cannot close M3 AST compilation", failure);
                }
            }
        };
    }

    void closeAfterFailure(Throwable failure) {
        try {
            close();
        } catch (IOException | RuntimeException | Error cleanup) {
            if (cleanup != failure) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try (URLClassLoader ignored = loader) {
                if (compilation != null) {
                    compilation.close();
                }
            }
        }
    }

    private static String singleOption(List<String> options, String prefix) {
        String value = null;
        for (String option : options) {
            if (option.startsWith(prefix)) {
                if (value != null) {
                    throw new IllegalArgumentException("Duplicate M3 AST compiler option: " + prefix);
                }
                value = option.substring(prefix.length());
            }
        }
        if (value == null) {
            throw new IllegalArgumentException("Missing M3 AST compiler option: " + prefix);
        }
        return value;
    }
}
