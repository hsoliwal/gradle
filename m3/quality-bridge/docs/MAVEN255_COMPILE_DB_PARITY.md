<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven #255 → Gradle LLVM compilation database handoff

Canonical producer: [merged Maven #255](https://github.com/hsoliwal/maven/pull/255), exact merged source `f403544494810fd78fc3f00b8c790935bbebfeaf`. It incorporates Maven #247, #250, and #253's safe LLVM compiler argument and output-sidecar discovery. Maven's `LlvmCompilationDatabase.publish` remains the sole owner of `target/m3-llvm/compile_commands.json`, which is **not** CMake's `build/cmake/compile_commands.json`.

Gradle `m3LlvmCompileDb` is lazily registered, explicitly invoked and `@UntrackedTask`. It passes exactly `llvm-compile-db <workspace> <manifest.tsv> <target>` to the independently SHA-256-pinned original Maven JAR. Extra provider JARs require their own exact SHA-256 pins; no custom parser, native Clang invocation, toolchain download or Gradle cache hit is admitted. The Maven CLI owns materialized translation units, real Clang argv, original source/sidecar checks, output path, atomic publication and byte-identical fixed point. Gradle checks only an exact versioned two-line receipt plus a regular bounded output with no symlink ancestors. The task uses direct `ExecOperations`, with no shell or default `build`/`check` dependency.

Example opt-in consumer (install the Gradle quality plugin separately):

```groovy
tasks.named('m3LlvmCompileDb') {
  engineJar.set(layout.projectDirectory.file('vendor/m3-polyglot-maven-plugin.jar'))
  engineSha256.set(providers.gradleProperty('m3.polyglot.sha256'))
  manifestFile.set(layout.projectDirectory.file('m3-build.tsv'))
  target.set('all')
  // No Gradle compilation or cache action is implied.
}
```

The source-owning Maven #255 launcher requires the original provider's selected Clang toolchain and may use `M3_LLVM_HOME`; installing or changing it is never a side effect of the Gradle task. The command emits `M3-LLVM-COMPILE-DB-V1` then `file<TAB><absolute target/m3-llvm/compile_commands.json>`; nothing else on stdout is admitted. Output presence does not certify headers, generated inputs, ABI, library/pass plugins, build cache eligibility or executable LLVM-fork provenance.

Verification gates: Java 17 strict no-Gradle argv/receipt contract; standalone Gradle fake CLI TestKit with positive/refusal/fixed-point probes; JDK17/21 GitHub Actions building the exact Maven #255 artifact and executing real original-C17-manifest TestKit; zero skipped test cases. Full Gradle reactor, Windows/macOS paths, source-built `hsoliwal/llvm-project` binary lineage, OpenRewrite SDK replay/fixed point, and native Clang execution are **separate**, not inferred from this integration. Review and DCO remain human gates.

Opt-in OpenRewrite consumer recipe: `io.synexia.gradle.recipe.EnableM3LlvmCompileDbBridge`; this recipe installs only the Gradle plugin. It does not perform the CLI task or assert generated output correctness.
