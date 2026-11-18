<!-- SPDX-License-Identifier: Apache-2.0 -->
# Original Maven #200 LLVM installation selectors through Gradle

**Source under test:** merged Maven [#200](https://github.com/hsoliwal/maven/pull/200),
commit `0249d0fd050ac89c89f7a0f8af1328cb944f076c`. The history includes
the original compiler SPI from Maven #198, installation/digest selection from
#199, the Objective-C++ alias and native checks from #217, and the shared
compiler/CMake/Clang-AST home precedence from #219. Maven #214 was closed
without merge, so its behavior is not presumed to exist.

## Ownership and invocation

The Gradle fork already has additive `m3PolyglotPlan`, `m3PolyglotRun`
and `m3PolyglotProviders` proxies. All three invoke Maven's own verified
polyglot JAR. Its original `LlvmCompilerProvider` interprets the unchanged
`m3-build.tsv` and selects the separately installed LLVM compiler. Gradle
must not copy Maven's selector parser, native process runner, receipt layout,
LLVM code or incremental native-cache decisions.

In an explicit Maven manifest, place selection arguments on the desired
LLVM compiler task:

```text
task\tcompile\t@m3:llvm:compiler:c\t.
arg\tcompile\t--m3-llvm-home=/reviewed/llvm-install
arg\tcompile\t--m3-llvm-sha256=REPLACE_WITH_64_HEX_DIGITS
arg\tcompile\t-c
arg\tcompile\tnative/hello.c
arg\tcompile\t-o
arg\tcompile\ttarget/hello.o
input\tcompile\tnative/hello.c
output\tcompile\ttarget/hello.o
```

The code block shows `\t` markers for field boundaries; a real manifest
uses **literal tab characters**. The source-verified Maven #200 example
at `tools/m3-polyglot-maven-plugin/examples/llvm/m3-build.tsv` is used in
the real cross-fork TestKit proof, including its separate header dependency
and output declarations.

Per-task `--m3-llvm-home=...` selects `bin/clang`, `bin/clang++`, `bin/flang`
or `bin/llc` as appropriate. `--m3-llvm-bin=...` retains the existing
bin-directory form; combining both on one task is refused. With no task
selector, the original Maven engine admits global properties
`m3.polyglot.llvm.home` then `m3.llvm.home`, followed by environment
`M3_LLVM_HOME` then `LLVM_HOME`, and finally its existing executable lookup.
It refuses duplicate/empty task selectors, invalid digests and absent
explicit installations; a reviewed home is not silently replaced by PATH.
The `objc++` and `objcpp` language identifiers both select original Clang++.

An `--m3-llvm-sha256=` checks **only executable bytes** after symlink
resolution; it is not a complete SDK, sysroot, header, linker, ABI, native
plugin or compiler cache key. The provider still executes in an incremental
Maven run and does not grant Gradle cache hits. No LLVM binaries are fetched,
built or bundled. Gradle's public and native task model remains intact.

## Independent source-pinned qualification

`.github/workflows/m3-gradle-maven200-llvm-selection.yml` builds exactly
merged Maven #200 on Temurin 17 and 21, runs its own `mvn verify`, and
passes its produced JAR, SHA-256-observed **host** Clang and original
source-controlled C fixtures to Gradle TestKit. The test performs real
native compilation twice and proves both runs execute; other tests verify
plan-only `objc++` / `objcpp`, wrong binary hash, missing explicit home,
duplicate selectors, and mutually exclusive home/bin refusal. These gates
do not claim the `hsoliwal/llvm-project` fork itself was built.

This capability **reuses** the existing opt-in OpenRewrite recipe
[`EnableM3LlvmProviderBridge`](../recipes/enable-m3-llvm-provider-bridge.yml).
It does not automatically execute a compiler, install a provider or
rewrite a consumer manifest. Review/replay that recipe independently before
promoting the parity lane.
