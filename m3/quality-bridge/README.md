<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 Gradle quality and polyglot bridge (experimental)

An **additive standalone Gradle plugin** in the existing Gradle fork. It does not
modify Gradle's default task scheduler, build cache, artifact resolution, toolchains,
configuration cache, Java compiler or existing OpenRewrite/CodeQL behavior.

## Plugin boundary

- `m3CodeqlSarif`: uses the separately installed official **CodeQL CLI** on an
  existing database and explicitly selected query suite, writing SARIF v2.1.0.
  The plugin never downloads CodeQL, builds a database, or uploads a report.
- `m3CodeqlEvidence`: invokes the original **M3 CodeQL Java 21 runner** from
  `hsoliwal/codeql` with its source-owned query catalog, Java call inventories,
  BQRS/CSV decoding and report schema. The runner, not Gradle, owns its stage
  evidence. A *fresh* output directory is required on every invocation.
- `m3SemgrepScan`: runs separately installed Semgrep with an **explicit local**
  rule file, JSON output and a failing exit status for findings. No autofix,
  registry rule downloads or implicit network provisioning.
- `m3RewritePreview`: only registered when the original
  `org.openrewrite.rewrite` plugin is **explicitly applied**; delegates to its
  `rewriteDryRun` task. Applying source changes remains an independent manual
  invocation of original `rewriteRun`, followed by DIFF/LINT/COMPILE/TEST gates.
- `M3LanguageToolTask`: opt-in, typed Gradle task for a compiler or other language
  tool with an explicit executable, argv, source files, working directory and
  artifacts directory; its exit status is authoritative. It does not shell out
  via a generated script or claim a complete cache key for an arbitrary compiler.

**No providers run merely because this plugin is applied.** Task configuration uses
Gradle properties and provider-backed locations; Gradle's normal graph and
concurrent scheduling remain in charge. External analysis tasks use `@UntrackedTask`
because the databases/tool implementations may mutate or have external inputs;
turning on caching for these tasks without proving completeness would be unsafe.

## Build and test independently

From the Gradle fork root (this directory is deliberately **not** in Gradle's
upstream production settings or reactor):

```sh
./gradlew -p m3/quality-bridge test
./gradlew -p m3/quality-bridge tasks --all --configuration-cache
```

The plugin targets Gradle's Java 17 baseline. The M3 CodeQL runner additionally
requires a separately selected Java 21 executable. Full Gradle TestKit,
configuration-cache reuse, actual CLI analysis and Windows/Linux builds must be
verified before removing the draft designation.

## Using it without modifying Gradle Core

In a consumer's `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild("/path/to/gradle/m3/quality-bridge")
    repositories { gradlePluginPortal() }
}
```

Then configure tasks in `build.gradle.kts` as in
[`examples/consumer.gradle.kts`](examples/consumer.gradle.kts). Supply an already
created CodeQL database, a legal/licensed CodeQL installation, local Semgrep
rules, or the M3 CodeQL runner JAR as required. The OpenRewrite Gradle plugin's
version and recipe artifacts are selected by the consuming build, not silently
added by this bridge. The user's `rewriteRun` remains explicitly invoked.

Gradle can orchestrate Ant, Make, Ninja, C/C++, Rust, Go, .NET, Python, Swift,
JavaScript and other toolchains when their tools are installed; this plugin
**does not implement every compiler**, resolve arbitrary language dependencies,
or make unsupported languages magically compile. Prefer each language's existing
Gradle plugin if it already offers safe task inputs/outputs and toolchain support.

## Compatibility and limitations

- Gradle `9.10.0` fork, Java 17 plugin target. No upstream Gradle source modified.
- M3JDK21 compiler integration remains in the original JavaCompile/worker owner.
- No CodeQL analysis output is treated as an instruction to rewrite code.
- Neither CodeQL nor OpenRewrite alone proves source/binary/behavioral compatibility.
- No cache hits, goal skipping, deletion of originals, patch application, output
  restoration or automatic model downloads are authorized.
- CodeQL CLI licensing and supported language extractors must be checked for the
  intended use; `hsoliwal/codeql` query sources do not bundle its CLI engine.
- OpenRewrite supports particular source and configuration formats; other
  languages need the appropriate provider rather than a fictitious universal AST.

The PR is opt-in and should stay draft until the actual TestKit test and external
CLI acceptance matrix passes; a standalone Java argument-vector smoke test is
not full Gradle qualification.
