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
## 2026-11-16 compatibility successor

The original static inspect API remains uncached. Its unavailable-provider path
is guarded for the declared Java 8 worker baseline, and SPI-specific code stays
in a private newer-JDK holder. The native producer may emit uniform LF or CRLF
records; successful inspection returns those original characters unchanged.
Mixed endings, invalid identity/rows, nonempty diagnostics, exhausted budgets
and a changed class digest remain errors. No new compiler invocation, classfile
parser, task input, output, cache key or scheduling decision is introduced.

The earlier local-stub paragraph records the historical introduction only.
The successor recipe and independent compatibility fixtures belong to Synexia's
existing m3-ast-build-reverse-20261017 family. Actual current gate receipts,
including original Gradle module, Java 8 runtime and real M3JDK21 provider
results, are required independently; source presence is not their PASS.

Line-ending adaptation follows the already-public Apache-2.0 Maven owner
DefaultM3BuildToolsService at 73ff0e382fe8e270db0a82caf22c7132050608b4.
Apache Maven, Copyright 2001-2026 The Apache Software Foundation.
This product includes software developed at The Apache Software Foundation
(https://www.apache.org/). Gradle retains its stronger stderr and header rules.

## Caller-owned receipt reuse

`M3JdkDependencyTool.newSession()` creates a fresh, explicitly owned Session.
The existing static `inspect` stays uncached. A consumer can scope repeated
queries with try-with-resources:

```java
try (M3JdkDependencyTool.Session facts = M3JdkDependencyTool.newSession()) {
    Optional<String> receipt = facts.inspect(selectedJdkHome, classFile, false);
    // Consume optional dependency facts under the existing consumer contract.
}
```

A Session admits at most **256 normalized path/mode identities** and **8 Mi
characters** across retained keys, separate digests and exact receipt strings.
It uses bounded sorted parallel arrays, selects the native provider once per
call, checks its name and actual Class identity, and hashes current class bytes
before a hit. Changing the provider class, including its classloader, clears
the whole old provider epoch. Missing providers remain unavailable. Same-size
changes with restored timestamps cannot authorize an old receipt. A hit avoids
a native analysis call; it does not avoid the byte-authentication read.

The existing strict native validator handles each miss. Malformed receipts,
nonempty diagnostics, provider failures and budget refusals are never retained.
A stale entry releases its slot/text accounting before replacement; a full
entry budget refuses a new identity before native analysis. Text capacity is
checked before retaining an otherwise valid result. Budget failures throw
IOException. Successful LF or CRLF receipt characters remain unchanged.

Inspection and close synchronize on each Session. Recursive inspection from a
provider callback fails explicitly. Close is idempotent, releases retained
arrays and provider-epoch identity, and makes subsequent use fail with
IllegalStateException. A provider callback cannot close the session and then
repopulate it with its in-flight result. A close from another thread waits for
the current inspection.

A Java 8 worker reports this optional provider unavailable. Selected external
JDKs still need a separately qualified process protocol. Sessions add no
automatic JavaCompile hook, global or daemon cache, task input/output, task
skip, incremental compilation decision, build-cache restore or scheduler rule.
They retain no AST, class-file bytes or live Gradle task/project models.

This storage adaptation follows the Apache Maven owner and attribution above.
Its canonical successor is the dated `gradle-native-tools-b-20261116` dataset
in the existing private recipe family, composed after the compatibility pass.
SDK replay, actual-source JUnit/runtime, complete native Gradle module, original
M3JDK provider and performance results remain separate qualification claims.
No wall-clock speedup is asserted by a provider-call-count test.
