<!--
Copyright 2026 Gradle and contributors.

Licensed under the Creative Commons Attribution-Noncommercial-ShareAlike 4.0 International License.
You may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://creativecommons.org/licenses/by-nc-sa/4.0/

Unless required by applicable law or agreed to in writing, software
is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied. See the License for the specific language governing
permissions and limitations under the License.
-->

# Optional M3 compiler input reuse

This custom Gradle fork preserves the existing compiler path by default. An
explicit `M3ASTCompilerArguments` on a `JavaCompile` task selects a locally
source-built `M3JavacASTProvider`. Its provider files are a normal Gradle
`@Classpath` input; provider identity and options are ordered `@Input` values.

The selected provider uses deferred source wrappers in the original javac
`getTask` invocation. It attaches an execution delegate to that original task.
The original `JavacTask` remains available to Gradle's `Elements`, `Trees`,
constant-dependency and source-to-class collectors. Annotation processor
decoration, diagnostics, backups and file-manager cleanup stay around the same
invocation. The provider and its class loader close per compilation.

The current source-donated M3 AST provider is qualified for a Java 21 compiler
worker. An unsupported compiler or missing selected provider fails during
compiler initialization. The admission hook itself retains Java 8-compatible
APIs and bytecode, following this fork's worker runtime target. It does not load
the Java 21 provider or read sources when no M3 option is selected.

Provider implementation and consumer dependencies belong to the locally built
custom distribution. There is no required private repository or external AST
service. The source projection recipe and exact source lineage are maintained
in the canonical Synexia convergence repository; the receiver owns the admitted
source. Providers can share lazily requested per-compilation facts with build
consumers and optional Rewrite conversion.

An explicit managed `parse`/`analyze`/`generate` lifecycle is a compatibility
choice. It must be compared with the original `task.call()` path for diagnostics,
annotation processors, multiple top-level classes, generated sources,
incremental mapping, constant dependents, cleanup and compiler events. A
successful adapter smoke test does not establish whole-Gradle compatibility or
a measured performance improvement. Command-line compiler execution bypasses
the JSR199 hook and is not evidence of shared AST reuse.

## Select the source-owned receiver provider

Use the custom fork's declared task-input owner in a JavaCompile configuration:

```groovy
import org.gradle.api.internal.tasks.compile.M3ASTCompilerArguments

tasks.withType(JavaCompile).configureEach {
    javaCompiler = javaToolchains.compilerFor {
        languageVersion = JavaLanguageVersion.of(21)
    }
    options.compilerArgumentProviders.add(new M3ASTCompilerArguments(
        'org.gradle.internal.m3.ast.M3GradleASTProvider',
        files(m3ProviderJar, m3AstJar, m3AstRuntimeDependencies, m3ConsumerJars),
        []))
}
```

The explicit FileCollection is part of Gradle's classpath fingerprint. Its provider
class must be loaded from those assets; a same-named class in a parent loader is
refused before initialization. Gradle supplies its own M3JavacASTProvider API to
the provider loader. The provider asset contains the receiving implementation and
its complete runtime closure, with source/license provenance retained by M3JDK.
The receiver's `m3/tooling/module-packs/build-support/gradle-provider` owns the
source builder and the real JavaCompile consumer/processor/runtime fixture.

`M3Compilation.fileManager` wraps the original Gradle manager without acquiring its
cleanup authority. `M3Compilation.originalDiagnostic` maps retained inputs back to
their original JavaFileObjects. The selected diagnostic adapter uses the original
javac Context's ClientCodeWrapper source-unwrapping policy while preserving
Gradle's trusted listener. Gradle's rich formatter receives its original
JCDiagnostic; the source-mapped view is used for outward source locations. This
preserves the rich generic-type details that a generic Diagnostic wrapper loses.

The receiving M3 provider executes ordinary `session.call()`. Consumer services
are created from its per-compilation asset loader. Their `onParsedUnit` callbacks
can independently inspect the same original compiler tree; a document/workspace
is created only on explicit demand. A full-analysis or OpenRewrite conversion
requires a separately selected and qualified phased contract. Normal compilation
does not change its lifecycle automatically to accommodate such a consumer.

The source tests retain the worker's existing Java 8 API/bytecode policy and run
the optional Java 21 admission contract with an independently compiled provider
asset. Run the original compiler subproject gates with Java 21, then build the
actual distribution:

```sh
./gradlew :java-compiler-worker:quickTest :java-compiler-plugin:quickTest -PtestJavaVersion=21
./gradlew :distributions-full:binDistributionZip
```

The receiver fixture additionally compares original bytecode, parse events,
annotation-processing rounds, source diagnostics, module compilation, unchanged
up-to-date behavior, classpath-asset invalidation and incremental constant
tracking. Its two independent consumer services compare the actual JavacTask and
CompilationUnitTree references and exercise lazy capture plus existing SQLite
cold replay. Those checks are distinct from the compiler subproject unit gates.
Their exact source-build logs, artifact custody and recipe hashes accompany the
review; ordinary upstream Gradle distributions do not contain this custom hook.

Source files enter as exact leaves. Wider L0–L5 facts/file/package/module/project/
repository summaries remain with the shared AST owner and are requested upward
from those leaves. Gradle retains its original build-task and incremental model.

## Canonical source and class content deltas

M3JDK21 owns content comparison in `com.m3.file.M3File`, implemented at
`m3/file/src/main/java/com/m3/file/M3File.java` in the product repository.
Its content fingerprints provide a common input for compiler, build, and IDE
consumers. Comparing retained fingerprints needs no source or class-file
parser. Capturing a fresh fingerprint can still require reading its content.

The `contentDelta` contract returns compact state flags:

| Flag | Value | Meaning |
| --- | --- | --- |
| `ADDED` | 1 | Previously known absent; now present. |
| `REMOVED` | 2 | Previously present; now known absent. |
| `CONTENT_CHANGED` | 4 | Both present with different recorded fingerprints. |

A `null` fingerprint means known absence, never an unread or inaccessible
input or a failed capture. Zero describes equal recorded content states.
Filesystem modification time and size cannot prove that bytes are unchanged.
A content fingerprint also does not make a concurrently modified file an
atomic snapshot.

Source bytes, class bytes, and build context are distinct inputs. Equal source
fingerprints do not preserve semantic facts after compiler options, ordered
classpath/module-path resolution, processors, or plugins change. Changed class
bytes require appropriate analysis; a content digest does not determine ABI,
inlined constants, annotations, generated resources, or runtime behavior.
Resources and required outputs retain their own task contracts.

Gradle already caches class analysis by class-file hash and classpath analysis
by filesystem snapshot hash. The receiving work must reuse those owners:

| Responsibility | Existing owner |
| --- | --- |
| Actual compiler task and incremental listeners | `JdkJavaCompiler`, `IncrementalCompileTask` |
| Original-task consumer admission | `M3JavacASTProvider`, `M3JavacASTTask` |
| Provider assets and options as task inputs | `M3ASTCompilerArguments` |
| Source/classpath change planning | `JavaRecompilationSpecProvider`, `CurrentCompilationAccess` |
| Affected compilation and successful result storage | `SelectiveCompiler`, `IncrementalResultStoringCompiler` |

The first three worker owners live in this subproject's
`src/main/java/org/gradle/api/internal/tasks/compile`; `IncrementalCompileTask`
lives in `platforms/jvm/java-compiler-plugin`. Planning and result storage live
under `platforms/jvm/language-java/src/main/java/org/gradle/api/internal/tasks/compile/incremental`.
The existing `M3JavacASTTaskTest` and `JavaCompilerArgumentsBuilderTest`
qualify compiler admission and option behavior.

This change documents the M3File contract; it does not install a delta receiver
in Gradle's planner. The provider is reached after Gradle has selected work,
so collecting facts there alone cannot establish additional task skipping.
A future receiver must preserve processor invalidation, multiple classes per
source, deleted outputs, compile transactions, and existing fallback decisions.
Retain the worker's current Java compatibility and original task ownership.

See the upstream
[incremental compilation and compile avoidance documentation](https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_compile)
and [task input/output checks](https://docs.gradle.org/current/userguide/incremental_build.html)
for the existing decisions. Actual distribution tests must establish any new
build avoidance or performance claim.
