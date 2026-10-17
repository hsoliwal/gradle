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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Real compiler and separately compiled provider assets exercise the admission boundary. */
public class M3JavacASTTaskTest {
    private static final String PROVIDER_NAME = "m3.fixture.M3FixtureProvider";
    private static final String PARENT_MARKER = "org.gradle.m3.test.parent.initialized";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private JavaCompiler compiler;
    private Path providerJar;

    @Before
    public void createIndependentProviderAsset() throws Exception {
        assumeTrue("This opt-in provider is qualified for the Java 21 worker", "21".equals(System.getProperty("java.specification.version")));
        compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        Path root = temporary.newFolder("provider source").toPath();
        Path source = root.resolve("m3/fixture/M3FixtureProvider.java");
        Files.createDirectories(source.getParent());
        Files.write(source, fixtureSource().getBytes(StandardCharsets.UTF_8));
        Path classes = Files.createDirectory(root.resolve("classes"));
        String workerClasses = new File(M3JavacASTProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(compiler.getTask(null, manager, null, Arrays.asList("-classpath", workerClasses, "-d", classes.toString()),
                null, manager.getJavaFileObjects(source.toFile())).call());
        }
        providerJar = root.resolve("provider with spaces.jar");
        try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(providerJar)); Stream<Path> files = Files.walk(classes)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                archive.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
                Files.copy(file, archive);
                archive.closeEntry();
            }
        }
    }

    @Test
    public void selectionIsExplicitAndRefusesInvalidAssetsBeforeInitialization() throws Exception {
        assertFalse(M3JavacASTTask.requested(Arrays.asList("-g", "--release", "21")));
        assertTrue(M3JavacASTTask.requested(Collections.singletonList("-XDm3ASTPath=missing")));
        List<String> options = options(temporary.newFolder("admission").toPath());
        assertThrows(IllegalArgumentException.class, () -> M3JavacASTTask.open(Collections.singletonList(options.get(0))));
        List<String> duplicate = new ArrayList<>(options);
        duplicate.add(options.get(0));
        assertThrows(IllegalArgumentException.class, () -> M3JavacASTTask.open(duplicate));
        List<String> missing = Arrays.asList(options.get(0), "-XDm3ASTPath=" + providerJar + File.pathSeparator + "missing-provider-asset");
        assertThrows(IllegalArgumentException.class, () -> M3JavacASTTask.open(missing));
        List<String> empty = Arrays.asList(options.get(0), "-XDm3ASTPath=" + providerJar + File.pathSeparator);
        assertThrows(IllegalArgumentException.class, () -> M3JavacASTTask.open(empty));
        assertThrows(IllegalArgumentException.class, () -> M3JavacASTTask.open(Arrays.asList("-XDm3AST=m3.fixture.Missing", options.get(1))));

        System.clearProperty(PARENT_MARKER);
        try {
            String name = getClass().getName() + "$M3ParentProvider";
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> M3JavacASTTask.open(Arrays.asList("-XDm3AST=" + name, options.get(1))));
            assertTrue(failure.getCause().getMessage().contains("outside the explicit provider assets"));
            assertNull("The parent implementation must not be initialized", System.getProperty(PARENT_MARKER));
        } finally {
            System.clearProperty(PARENT_MARKER);
        }
    }

    @Test
    public void originalCompilerRunsOnceWithProcessorsModulesAndIsolatedProviderState() throws Exception {
        for (int invocation = 0; invocation < 2; invocation++) {
            Path root = temporary.newFolder("compilation-" + invocation).toPath();
            List<String> options = options(root);
            M3Source source = new M3Source("class Sample { static final int VALUE = 41; }");
            DiagnosticCollector<JavaFileObject> outward = new DiagnosticCollector<>();
            try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
                 M3JavacASTTask custody = M3JavacASTTask.open(options)) {
                Iterable<? extends JavaFileObject> inputs = custody.prepare(Collections.singletonList(source), StandardCharsets.UTF_8, manager);
                assertEquals("Admission must not read a source", 0, source.reads.get());
                JavacTask original = originalTask(custody, manager, outward, options, inputs);
                assertEquals("getTask must preserve deferred compiler reads", 0, source.reads.get());
                JavaCompiler.CompilationTask execution = custody.closeAfter(custody.attach(original, () -> 0));
                M3Processor processor = new M3Processor();
                execution.setProcessors(Collections.singletonList(processor));
                execution.setLocale(Locale.ROOT);
                execution.addModules(Collections.singletonList("java.logging"));
                assertTrue(execution.call());
                assertTrue(processor.rounds > 0);
                assertEquals(1, processor.generated);
                assertEquals(1, source.reads.get());
                assertTrue(Files.isRegularFile(root.resolve("classes/Sample.class")));
                assertTrue(Files.isRegularFile(root.resolve("classes/M3Generated.class")));
            }
            String events = read(root.resolve("events.txt"));
            assertEquals(events, 1, occurrences(events, "instance=1\n"));
            assertFalse(events, events.contains("instance=2"));
            assertEquals(events, 1, occurrences(events, "execution\n"));
            assertEquals(events, 1, occurrences(events, "start:COMPILATION\n"));
            assertEquals(events, 1, occurrences(events, "finish:COMPILATION\n"));
            assertEquals(events, 2, occurrences(events, "finish:PARSE\n"));
            assertEquals(events, 1, occurrences(events, "close\n"));
            assertTrue(events, events.contains("fileManager\n"));
            assertTrue(events, events.contains("setProcessors\n"));
            assertTrue(events, events.contains("setLocale\n"));
            assertTrue(events, events.contains("addModules\n"));
        }
    }

    @Test
    public void originalDiagnosticsAndFailureCleanupCrossTheSameBoundary() throws Exception {
        Path root = temporary.newFolder("diagnostics").toPath();
        List<String> options = options(root);
        M3Source source = new M3Source("class Sample { int value = ; }");
        DiagnosticCollector<JavaFileObject> outward = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
             M3JavacASTTask custody = M3JavacASTTask.open(options)) {
            Iterable<? extends JavaFileObject> inputs = custody.prepare(Collections.singletonList(source), StandardCharsets.UTF_8, manager);
            JavacTask original = originalTask(custody, manager, outward, options, inputs);
            assertFalse(custody.closeAfter(custody.attach(original, () -> 1)).call());
            assertFalse(outward.getDiagnostics().isEmpty());
            for (Diagnostic<? extends JavaFileObject> diagnostic : outward.getDiagnostics()) {
                assertSame(source, diagnostic.getSource());
            }
        }
        assertTrue(read(root.resolve("events.txt")).contains("diagnostic\n"));

        Path failed = temporary.newFolder("failed execution").toPath();
        List<String> failureOptions = options(failed);
        failureOptions.add("-XDm3TestCallFailure=true");
        failureOptions.add("-XDm3TestCloseFailure=true");
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
             M3JavacASTTask custody = M3JavacASTTask.open(failureOptions)) {
            Iterable<? extends JavaFileObject> inputs = custody.prepare(Collections.singletonList(new M3Source("class Sample {}")), StandardCharsets.UTF_8, manager);
            JavacTask original = (JavacTask) compiler.getTask(null, custody.fileManager(manager), null, failureOptions, null, inputs);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> custody.closeAfter(custody.attach(original, () -> 0)).call());
            assertEquals("selected execution failed", failure.getMessage());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals("selected cleanup failed", failure.getSuppressed()[0].getMessage());
        }
        assertEquals(1, occurrences(read(failed.resolve("events.txt")), "close\n"));
    }

    /** Keep this fixture on the worker's original Java 8 API surface while exercising the Java 21 Context. */
    private JavacTask originalTask(M3JavacASTTask custody, JavaFileManager manager,
                                   DiagnosticListener<? super JavaFileObject> diagnostics, List<String> options,
                                   Iterable<? extends JavaFileObject> inputs) throws ReflectiveOperationException {
        Class<?> contextType = Class.forName("com.sun.tools.javac.util.Context");
        Object context = contextType.getConstructor().newInstance();
        Object listener = M3JavacASTTask.class.getDeclaredMethod("listener", DiagnosticListener.class, contextType)
            .invoke(custody, diagnostics, context);
        return (JavacTask) compiler.getClass().getMethod("getTask", Writer.class, JavaFileManager.class,
            DiagnosticListener.class, Iterable.class, Iterable.class, Iterable.class, contextType)
            .invoke(compiler, null, custody.fileManager(manager), listener, options, null, inputs, context);
    }

    private List<String> options(Path root) throws IOException {
        Path classes = Files.createDirectory(root.resolve("classes"));
        return new ArrayList<>(Arrays.asList("-XDm3AST=" + PROVIDER_NAME, "-XDm3ASTPath=" + providerJar,
            "-XDm3TestEvents=" + root.resolve("events.txt"), "--release", "21", "-d", classes.toString()));
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(value, at)) >= 0; at += value.length()) {
            count++;
        }
        return count;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static final class M3Source extends SimpleJavaFileObject {
        private final String text;
        private final AtomicInteger reads = new AtomicInteger();

        private M3Source(String text) {
            super(URI.create("string:///Sample.java"), Kind.SOURCE);
            this.text = text;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            reads.incrementAndGet();
            return text;
        }
    }

    private static final class M3Processor extends AbstractProcessor {
        private int rounds;
        private int generated;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Collections.singleton("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            rounds++;
            if (!round.processingOver() && generated == 0) {
                generated++;
                try (Writer writer = processingEnv.getFiler().createSourceFile("M3Generated").openWriter()) {
                    writer.write("final class M3Generated {}");
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
            return false;
        }
    }

    /** A visible parent implementation must never satisfy an explicit asset selection. */
    public static final class M3ParentProvider implements M3JavacASTProvider {
        static {
            System.setProperty(PARENT_MARKER, "true");
        }

        @Override
        public M3Compilation prepare(Iterable<? extends JavaFileObject> sources, Charset encoding,
                                     List<String> options, StandardJavaFileManager manager,
                                     Supplier<List<Diagnostic<? extends JavaFileObject>>> diagnostics) {
            throw new AssertionError("parent provider must not be selected");
        }
    }

    private static String fixtureSource() {
        return String.join("\n",
            "package m3.fixture;",
            "import org.gradle.api.internal.tasks.compile.M3JavacASTProvider;",
            "import com.sun.source.util.*;",
            "import javax.annotation.processing.Processor;",
            "import javax.tools.*;",
            "import java.io.*; import java.nio.charset.*; import java.nio.file.*;",
            "import java.util.*; import java.util.function.*;",
            "public final class M3FixtureProvider implements M3JavacASTProvider {",
            " private static int instances; private final int instance = ++instances;",
            " public M3Compilation prepare(Iterable<? extends JavaFileObject> sources, Charset encoding,",
            "  List<String> options, StandardJavaFileManager manager, Supplier<List<Diagnostic<? extends JavaFileObject>>> diagnostics) {",
            "  String selected = options.stream().filter(s -> s.startsWith(\"-XDm3TestEvents=\")).findFirst().get();",
            "  Path events = Paths.get(selected.substring(\"-XDm3TestEvents=\".length()));",
            "  Consumer<String> record = message -> { try { Files.write(events, (message + \"\\n\").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }",
            "   catch (IOException e) { throw new UncheckedIOException(e); } };",
            "  record.accept(\"instance=\" + instance);",
            "  return new M3Compilation() {",
            "   public Iterable<? extends JavaFileObject> sources() { return sources; }",
            "   public JavaFileManager fileManager(JavaFileManager original) { record.accept(\"fileManager\"); return original; }",
            "   public Diagnostic<? extends JavaFileObject> originalDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) { record.accept(\"diagnostic\"); return diagnostic; }",
            "   public JavaCompiler.CompilationTask attach(JavacTask original, IntSupplier errors) {",
            "    original.addTaskListener(new TaskListener() { public void started(TaskEvent e) { record.accept(\"start:\" + e.getKind()); }",
            "     public void finished(TaskEvent e) { record.accept(\"finish:\" + e.getKind()); } });",
            "    return new JavaCompiler.CompilationTask() {",
            "     public void setProcessors(Iterable<? extends Processor> processors) { record.accept(\"setProcessors\"); original.setProcessors(processors); }",
            "     public void setLocale(Locale locale) { record.accept(\"setLocale\"); original.setLocale(locale); }",
            "     public void addModules(Iterable<String> modules) { record.accept(\"addModules\"); original.addModules(modules); }",
            "     public Boolean call() { record.accept(\"execution\"); if (options.contains(\"-XDm3TestCallFailure=true\")) throw new IllegalStateException(\"selected execution failed\"); return original.call(); }",
            "    };",
            "   }",
            "   public void close() throws IOException { record.accept(\"close\"); if (options.contains(\"-XDm3TestCloseFailure=true\")) throw new IOException(\"selected cleanup failed\"); }",
            "  };",
            " }",
            "}", "");
    }
}
