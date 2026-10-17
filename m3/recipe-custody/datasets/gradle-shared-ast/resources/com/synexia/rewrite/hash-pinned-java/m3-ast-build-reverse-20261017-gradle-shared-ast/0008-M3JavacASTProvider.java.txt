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
import org.jspecify.annotations.NullMarked;

import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.Closeable;
import java.nio.charset.Charset;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Optional source-donated compiler integration selected for one compilation.
 *
 * <p>The provider must retain the exact supplied compiler and input semantics.
 * It must not create a second compiler task or parse the same input again.
 * Gradle owns the file manager and the surrounding processor/incremental wrappers.
 */
@NullMarked
public interface M3JavacASTProvider {
    /**
     * Prepare deferred source wrappers without reading or parsing their contents.
     *
     * @param sources the actual compiler source inputs
     * @param encoding the selected compiler source encoding
     * @param actualOptions the complete compiler options, in their original order
     * @param fileManager the actual manager, configured by the subsequent getTask call
     * @param diagnostics an isolated snapshot supplier for the original diagnostics
     * @return one compilation's custody and execution adapter
     */
    M3Compilation prepare(
        Iterable<? extends JavaFileObject> sources,
        Charset encoding,
        List<String> actualOptions,
        StandardJavaFileManager fileManager,
        Supplier<List<Diagnostic<? extends JavaFileObject>>> diagnostics
    );

    /** Custody for one compiler invocation; it does not own Gradle's file manager. */
    interface M3Compilation extends Closeable {
        /** Deferred inputs passed to the original compiler's getTask call. */
        Iterable<? extends JavaFileObject> sources();

        /**
         * Preserve original source-file identities at the existing Gradle manager boundary.
         * The returned manager must delegate to this wrapper and must not take ownership.
         *
         * @param original the existing Gradle manager, including its processor-loader behavior
         * @return the same manager or an identity-preserving forwarding manager
         */
        default JavaFileManager fileManager(JavaFileManager original) {
            return original;
        }

        /**
         * Restore the original source-file identity in outward diagnostics without formatting.
         *
         * @param diagnostic the compiler's diagnostic
         * @return the outward diagnostic with unchanged positions, codes and messages
         */
        default Diagnostic<? extends JavaFileObject> originalDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
            return diagnostic;
        }

        /**
         * Attach to the original task before Gradle installs its collectors.
         *
         * @param originalTask the actual javac task retained by Gradle's listeners
         * @param errorCount a supplier retaining the original compiler error counter
         * @return an execution delegate for that same task
         */
        JavaCompiler.CompilationTask attach(JavacTask originalTask, IntSupplier errorCount);
    }
}
