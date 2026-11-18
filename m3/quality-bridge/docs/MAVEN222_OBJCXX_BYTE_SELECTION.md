<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven #222: both Objective-C++ aliases select pinned Clang++

Merged Maven [#222](https://github.com/hsoliwal/maven/pull/222) at
`36bc166d236950a5b220236516f5da90cc044571` completes the
original native provider's independent check that both `objc++` and
`objcpp` select the *Clang++* driver and verify its executable bytes.
This follows original Maven #198, #200, #217 and #219. It adds test
evidence to the Maven implementation; it is **not** a second LLVM
compiler or selector implementation in Gradle.

The existing Gradle `m3PolyglotPlan` and `m3PolyglotRun` tasks forward
the same source-owned `m3-build.tsv` to the original, digest-pinned
Maven engine. Its `LlvmCompilerProvider`, not Gradle, interprets
`@m3:llvm:compiler:objc++` and `@m3:llvm:compiler:objcpp`, selects
`bin/clang++`, and compares the `--m3-llvm-sha256=` receipt after
symlink resolution. `bin/clang` must not be accepted as an alias for
Clang++ when the two executables have different bytes.

## Qualification

The independent `.github/workflows/m3-gradle-maven222-objcxx-pin.yml`
pins Maven #222, executes the **original Maven `verify`** and runs
Gradle TestKit on Java 17 and 21. The Gradle acceptance contains:

1. **Synthetic plan-only bytes**: two deliberately different, non-executable
   `clang` and `clang++` files. Planning either Objective-C++ language with
   the Clang++ digest succeeds without execution; the Clang digest is
   rejected for both. No synthetic file is presented as a usable compiler.
2. **Real native compiler**: the installed **host** Clang++ runs both
   language aliases on a minimal `.mm` C++ translation unit, producing
   separate object files. Planning first must produce no object.
3. **Cache boundary**: a selected compiler digest does not prove headers,
   transitive inputs, sysroot, runtimes or native cache eligibility.

The host toolchain is not claimed to be built from the user's
`hsoliwal/llvm-project` fork; full original native ABI qualification
remains separate. The plugin does not install an LLVM distribution,
download compilers or alter native Gradle configuration/build caches.

This lane **reuses** the existing source-owned
[`EnableM3LlvmProviderBridge`](../recipes/enable-m3-llvm-provider-bridge.yml)
OpenRewrite recipe. Applying that recipe, executing native tasks and
promoting results require independent review and replay.
