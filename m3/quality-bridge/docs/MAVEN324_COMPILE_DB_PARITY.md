<!-- SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 Gradle — merged Maven #324 LLVM compilation-database cross-fork acceptance

## Historical source owner and additive qualification

The existing `m3LlvmCompileDb` Gradle task was merged in
[Gradle #71](https://github.com/hsoliwal/gradle/pull/71) and retains its
[Gradle #76](https://github.com/hsoliwal/gradle/pull/76) link-refusal
qualification and [Gradle #81](https://github.com/hsoliwal/gradle/pull/81)
empty/truncated database refusal. The historical
`.github/workflows/m3-gradle-maven255-compile-db.yml` remains pinned to
[Maven #255](https://github.com/hsoliwal/maven/pull/255), revision
`f403544494810fd78fc3f00b8c790935bbebfeaf`. Do **not** rewrite
or retroactively re-label those prior execution results.

This separate workflow and one real-engine TestKit case qualify the
newer **merged** [Maven #324](https://github.com/hsoliwal/maven/pull/324)
producer at exact merge revision
`6efb7b09d35b377f817f6da156efef47a915d4de`. The new
`-Wp,` and opaque-backend checks live only in Maven's original
`LlvmCompilationDatabase`, never in Gradle's Java task, and no extra
native tool adapter/parser is introduced.

## Original-engine execution contract

The new test `M3Llvm324ForwardedDepfileRealEngineTest` invokes
`m3LlvmCompileDb` against the original source-built Maven plugin
JAR, whose complete bytes are independently SHA-256-pinned by the
existing Gradle task. The test first exports an ordinary original
Clang C17 task and records its module-owned JSON bytes and timestamp.

It then modifies the original `m3-build.tsv` manifest by adding
`arg<TAB>compile<TAB>-Wp,-MD,target/hidden.d` and separately
declaring `target/hidden.d` as an output, to prove that *merely naming
the hidden depfile does not qualify arbitrary forwarded native
compiler effects*. The second original Maven CLI invocation must fail
with `unqualified Clang output/forwarding option`, preserving the
previous accepted database bytes and timestamp. No object file,
depfile or M3DB state may be emitted; the metadata exporter must not
execute a native compiler.

This is deliberately distinct from the original
`M3LlvmCompileDbRealEngineTest` positive and digest-negative
tests; the historical suite is unchanged.

## Required acceptance and provenance

The new JDK17/JDK21 workflow:

1. Runs the original Gradle #71/#76/#81 historical source guard
   unchanged; does not re-pin Maven #255 in place.
2. Checks out `hsoliwal/maven` at exact
   `6efb7b09d35b377f817f6da156efef47a915d4de`.
3. Runs the **original Maven** plugin `mvn verify` in each JDK lane,
   including its compiler-argument refusal contracts, with original
   Clang selected from the runner's installed toolchain.
4. Reproduces real Clang `-Wp,-MD` generation on the original
   `native/hello.c` and `native/message.h` fixture; the depfile
   must contain the header dependency.
5. Runs Gradle TestKit on the three named original/source-sealed
   classes, which comprise **4 synthetic + 2 historic real-engine +
   1 new real-engine = 7 tests per JDK**.
6. Parses the original Gradle JUnit XML and accepts only the seven
   exact method names, with no skipped/failed/errored tests, foreign
   suites or missing reports. Uploads original Maven and Gradle
   JUnit artifacts regardless of the result.

The standalone Maven build on JDK17/21 and the actual real TestKit
invocation (not just a matching fixture or green YAML) are mandatory.
Neither a source code grep nor the original distribution Clang can
prove that the installed binary was built from the audited LLVM
source fork. That remains a separate qualification gate.

## Strict ownership and compatibility

The only new Gradle source file is a **test**. There are no changes
to `M3ToolProvider`, `M3LlvmCompileDbTask`, its Java public
signatures, original Gradle task registration, build/check wiring,
CMake's compilation database or the original Clang parser. The
original Gradle task stays `@UntrackedTask`: no native output reuse,
Maven M3DB incremental skip, restored artifact or Gradle cache hit
is admitted by these IDE metadata tests.

The canonical OpenRewrite producer and full sorted SHA-256 manifests
live in `hsoliwal/com.synexia` under
`m3-gradle-maven324-compdb-20261128`, using the existing
`M3HashPinnedJavaSnapshotRecipe` and
`M3HashPinnedTextSnapshotRecipe`. The Java source and YAML/Markdown
changes must first be replayed against exact bytes, reach a fixed
point and refuse occupied/mutated source owners before independent
Gradle CI is considered as a publication gate.

**Qualification boundary:** an authored workflow is not a passing
workflow, a deterministic source template is not an executed
OpenRewrite SDK result, and host Clang is not an exact-fork LLVM
binary attestation. Windows/macOS, JNI/ORC, cross-sysroot/toolchain,
native complete input closure and cache admission remain separate.
