<!-- SPDX-License-Identifier: Apache-2.0 -->
# Gradle Java compiler-worker access to M3JDK21 build tools

M3JDK21 remains the integrated SDK and owns classfile dependency
analysis inside the original `jdk.jdeps` module. This Gradle worker
addition exposes `M3JdkDependencyTool.inspect(selectedJdkHome, classFile,
apiOnly)` as an **optional** native consumer. It does not change the
existing `JavaCompile` task, compiler options, Gradle worker
serialization, incrementality or Java toolchain selection.

A selected toolchain differing from the worker's actual Java runtime
causes a miss rather than misusing another JDK's provider. A JVM without
`ToolProvider.findFirst("m3-class-deps")` also returns a miss.
An installed provider receives the .class path and mode and returns
`M3JDK21_CLASS_DEPS_V1` receipts. Receipts are bounded (4 Mi
characters), schema-checked and validated against the class's exact
SHA-256 **after** the native analysis to reject stale output. There is
no Gradle-owned bytecode parser or competing AST. Original Gradle
task outputs and build-cache contracts remain authoritative.

Local Java 17 strict javac compiled the Gradle adapter and its JUnit4
source using minimal JUnit test stubs; executable tests reported
`GRADLE_M3_JDK_BUILD_TOOLS_PASS tests=2` (real file bytes, native
provider fixture, selected JDK check, symlink/forgery/midscan mutation
refusals). The original Gradle test worker was **not** run.

**Pending:** actual `./gradlew :java-compiler-worker:test`, patched
M3JDK21 ToolProvider SPI discovery, external toolchain process
protocol, original Maven/Gradle cross-build and JNI verification,
parallel worker scheduling, canonical OpenRewrite SDK/fixed-point
recipe proof and comparative performance. A plugin consumer still
must opt into the native receipt and preserve its rule-engine behavior.