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
- `m3ReviewAnalysis`: **explicit**, read-only consumer of the same strict
  `M3-STATIC-ANALYSIS-V1` report format used by Maven's native static-analysis
  evidence service. Requires a SHA-256 from the original producing workflow.
  Uses bounded, array-backed Java 17 parsing; never runs analyzers or infers
  source completeness. Can optionally publish adapter-reported findings through
  Gradle's native Problems API, and optionally fail on source-declared errors or
  non-COMPLETE status. Both options default to **false**. It is not attached to
  Gradle's normal `check` task automatically.
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

## Share Maven's all-language manifest engine

The opt-in `m3PolyglotPlan` and `m3PolyglotRun` tasks use the **same** Java 17
`io.synexia.maven.polyglot.PolyglotCli` class from
`hsoliwal/maven/tools/m3-polyglot-maven-plugin`; Gradle does not fork the manifest
parser or pretend to own the native compilers. Build that Maven plugin JAR using
the reviewed Maven source revision, compute its SHA-256, and supply both values:

```groovy
plugins { id 'io.synexia.m3.quality' }

['m3PolyglotPlan', 'm3PolyglotRun'].each { taskName ->
    tasks.named(taskName) {
        engineJar.set(layout.projectDirectory.file('vendor/m3-polyglot-maven-plugin.jar'))
        engineSha256.set(providers.gradleProperty('m3.polyglot.sha256'))
        manifestFile.set(layout.projectDirectory.file('m3-build.tsv'))
        target.set('all')
        timeoutSeconds.set(900)
        // Optional external providers, each separately pinned:
        // providerJars.from(layout.projectDirectory.file('vendor/m3-rust-provider.jar'))
        // providerJarSha256.put('m3-rust-provider.jar',
        //     providers.gradleProperty('m3.rust.provider.sha256'))
    }
}

// Optional, only for the explicitly requested run task; never for planning.
tasks.named('m3PolyglotRun') {
    incremental.set(false)
    // Only if incremental is true: legacy (default), file, m3db,
    // sqlite, h2, or an explicitly installed ext-* backend.
    stateBackend.set('legacy')
    approveRewriteApply.set(false)
}
```

Invoke `./gradlew m3PolyglotPlan` to validate and display the dependency order,
then explicitly invoke `./gradlew m3PolyglotRun` to execute native commands.
Both tasks refuse a missing JAR, missing digest, mismatch, symlinked JAR or
manifest outside the workspace. An absolute, existing, regular manifest is
required. No tool is downloaded and no model is invoked. These are untracked
Gradle tasks because arbitrary toolchains, environments and generated outputs
cannot yet be proven hermetic. Neither task is attached to `build` or `check`.

The Maven CLI still accepts its original four-argument form and adds optional
`--timeout-seconds`, `--incremental`, `--state-backend` and
`--approve-rewrite-apply` for explicit `run` actions. Incremental shortcuts are **off by default** and apply only to
ordinary tasks with declared inputs and outputs. CodeQL/OpenRewrite/M3 provider
tasks always re-execute. An input stamp is not proof of toolchain, environment,
ABI, output-content or transitive-dependency equivalence; do not use it as an
unqualified build-cache correctness claim. Rewrite application requires explicit
separate approval and the source-owned recipe/compile/test gates.

### Maven PRs #143 and #147: opt-in module-local incremental persistence

Maven [PR #143](https://github.com/hsoliwal/maven/pull/143) has merged the
portable CLI contract `--state-backend legacy|file|m3db`. Merged
[PR #147](https://github.com/hsoliwal/maven/pull/147) extends it with
`sqlite`, `h2` and provider-defined `ext-*` backends, plus a module-wide
run lock and failure-safe invalidation. The Gradle fork
now passes the selected backend to **Maven's pinned engine** without copying
its parser, state format, task graph, locking or native MIndexDB implementation.
The Gradle task is still `@UntrackedTask`: M3DB is **Maven polyglot task
state**, not authorization to skip Gradle tasks or replace Gradle's own
configuration cache, build cache, incremental compilation or Worker API.

With the opt-in `incremental` setting enabled:

```groovy
tasks.named('m3PolyglotRun') {
    incremental.set(true)
    stateBackend.set('file') // atomically sealed .m3/module-state-v1.bin
    // or 'm3db' when the independently verified native MIndexDB runtime
    // is available on the pinned engine's classpath
    // or 'sqlite' / 'h2' with an explicitly installed, SHA-256-pinned
    // JDBC driver JAR via providerJars and providerJarSha256
    // or 'ext-local' with a separately installed, pinned backend provider
}
```

Alternatively `-Pm3.polyglot.stateBackend=sqlite` (or another supported
backend) configures the
`m3PolyglotRun` task only. `m3PolyglotPlan` always defaults to `legacy`
even when the project property is set, so a plan remains read-only.
The existing signatures and default argv remain unchanged. A nonlegacy
backend on a nonincremental run, an unknown backend, or a nonlegacy backend
configured explicitly for planning is rejected *before* launching the engine.
The `file` and `m3db` modes require a Maven engine including PR #143;
`sqlite`, `h2` and `ext-*` require an engine including PR #147. Its exact
reviewed source revision and binary SHA-256 must be pinned; the CI contract
uses merge commit `4d4976f9cf6f62b5f007cf5ab80ee92c67c0f3f7`.
No MIndexDB runtime, JDBC driver or external backend is bundled or downloaded.
Unknown backend names are rejected by Gradle; missing optional drivers and
providers are refused by the original Maven engine. The JDBC drivers are
ordinary explicit classpath JARs here, not a Gradle database reimplementation.

Maven's standalone engine verifies declared output contents for reuse and
conservatively rehashes where ctime/file-key evidence is unavailable.
PR #147 adds cooperating-process module locks and preserves a sealed v1 image;
its SQLite/H2 runtime-driver interoperability, cross-platform crash recovery
and native MIndexDB integration remain separate qualification gates. Competing
Maven PRs [#144](https://github.com/hsoliwal/maven/pull/144)–
[#146](https://github.com/hsoliwal/maven/pull/146) remain open and are not
silently composed. Merged Maven
[#148](https://github.com/hsoliwal/maven/pull/148) caches *syntax fingerprints*
inside the separate smart-builder module; that is neither a successful-build
receipt nor a Gradle task-cache hit, and is not imported by this CLI bridge.

### Maven-owned compiler, interpreter, transpiler, atomizer, patternizer

The same Maven engine now has a versioned `ServiceLoader<M3ToolProvider>`
interface. A manifest may select an explicitly installed operation with a
`@m3:<provider>:<capability>:<language>` tool. The five capabilities are
`compiler`, `interpreter`, `transpiler`, `atomizer` and `patternizer`.
The built-in Java compiler provider (`@m3:jdk:compiler:java`) forwards options,
including `-Xplugin:M3AST` when available, to the original JDK/M3JDK compiler.
Other languages and operations require independent installed providers; none
are inferred from an Eclipse nature or enabled automatically.

Use the **read-only** `m3PolyglotProviders` task to inspect the provider set
that Maven's pinned runtime actually loaded:

```groovy
tasks.named('m3PolyglotProviders') {
    engineJar.set(layout.projectDirectory.file('vendor/m3-polyglot-maven-plugin.jar'))
    engineSha256.set(providers.gradleProperty('m3.polyglot.sha256'))
    // Optional: the *same* extra classpath as m3PolyglotPlan/Run.
    // providerJars.from(layout.projectDirectory.file('vendor/m3-rust-provider.jar'))
    // providerJarSha256.put('m3-rust-provider.jar',
    //     providers.gradleProperty('m3.rust.provider.sha256'))
}
```

`./gradlew m3PolyglotProviders` calls Maven's original `PolyglotCli providers`
and writes the validated, deterministically ordered
`M3-POLYGLOT-PROVIDERS-V1` response into
`build/reports/m3/polyglot-providers.tsv`. It checks a pinned SHA-256, rejects
malformed/unsorted results, normalizes Windows CRLF and atomically publishes
only bounded UTF-8. No compiler or analyzer is invoked by registration or
inventory. The existing `m3PolyglotPlan`/`m3PolyglotRun` entry points pass
`@m3` manifest tasks through to that same Maven engine.

An external provider must be installed on the **Maven engine's runtime
classpath** with its `META-INF/services` descriptor. Configure `providerJars`
and `providerJarSha256` identically on the inventory and execution tasks.
Every provider filename must be unique, every JAR is separately SHA-256
verified and size-bounded, and no undeclared/extra checksum keys are accepted.
The ordered classpath is deterministic. These are explicitly selected local
artifacts, never silently downloaded, and a missing provider is not a fallback
to an arbitrary executable. Other Gradle and Maven classloaders remain unchanged.

The consumer recipe `io.synexia.maven.recipe.EnableM3ToolProviderSPI` can
register the Maven plugin without binding any goal. The M3AST source facts,
M3File identities/digests and M3String immutable atoms remain in their own
compiler/M3JDK owners, while Maven owns provider discovery and execution
orchestration and Gradle stays an explicit proxy.

## Read-only Eclipse project model (Maven-owned parser)

The explicit `m3EclipseModel` task now delegates to the **same**
`io.synexia.maven.polyglot.EclipseModelCli` shipped by Maven's
`tools/m3-polyglot-maven-plugin`. It uses no forked Eclipse XML parser, no
embedded Maven, and no extra Gradle model cache. The task requires a SHA-256
pin for the existing engine JAR and an Eclipse `.project` inside the Gradle
project checkout. It writes the producer's `m3-eclipse-project-v1` JSON into
`build/reports/m3/eclipse-model.json`, using bounded UTF-8 output and an atomic
replace after successful completion. It runs **only when explicitly invoked**.

```groovy
plugins { id 'io.synexia.m3.quality' }

tasks.named('m3EclipseModel') {
    engineJar.set(layout.projectDirectory.file('vendor/m3-polyglot-maven-plugin.jar'))
    engineSha256.set(providers.gradleProperty('m3.polyglot.sha256'))
    eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))
}
```

Run `./gradlew m3EclipseModel`. This is a descriptor-and-hash snapshot only;
JDT working copies, CDT native compilation, RustDT, PyDev and LSP server
lifecycles remain with their original Eclipse owners until their adapters are
independently implemented and qualified. The intended shared ownership is
**M3File** (canonical file identity and deltas) → **M3String** (immutable shared
source atoms and ranges) → **M3AST** (versioned language AST/atom references) →
M3Scale evidence → Maven/Gradle build execution. This task does **not** claim
that OS-shared M3String storage or AST adapters already exist in Gradle. The
M3AST compiler option in this fork remains an independent explicit JavaCompile
setting; neither task silently enables the other.

## Maven-to-Gradle static-analysis report compatibility

The Maven fork's `M3StaticAnalysisService` publishes source-bound analyzer
evidence through Maven's original session. A qualified producer may export
the **exact normalized v1 report bytes** and their SHA-256 for a separate
Gradle build to review. Gradle checks that expected digest; it does not
inherit Maven session membership or authenticate which analyzer actually ran.

```groovy
plugins {
    id 'io.synexia.m3.quality'
}

tasks.named('m3ReviewAnalysis') {
    reportFile.set(layout.projectDirectory.file('analysis.tsv'))
    expectedSha256.set(providers.gradleProperty('m3.analysis.sha256'))
    publishProblems.set(false)      // opt-in Gradle Problems API display
    failOnReportedErrors.set(false) // opt-in report policy
}
```

The v1 row format is in
[`hsoliwal/maven/docs/M3-STATIC-ANALYSIS.md`](https://github.com/hsoliwal/maven/blob/master/docs/M3-STATIC-ANALYSIS.md).
No Gradle Problems event is created unless the user explicitly executes
`m3ReviewAnalysis` and enables publication. The displayed messages are
**analyzer adapter claims**, never an independent safety or clean-code verdict.
CodeQL/PMD/Checkstyle/SpotBugs/other engines remain responsible for execution
and mapping their own findings. The digest is byte custody, not proof of
source, rules, toolchain, classpath or producer completeness.

SpotBugs integration uses the same original adapter JAR and the same opt-in
m3NormalizeAnalyzerXml task, with format set to SPOTBUGS and the already
produced original BugCollection XML selected as originalXml. The adapter
preserves the original bug type, priority and SourceLine metadata. A missing
source path remains an unlocated project-wide finding, not a guessed Java
source file. Priority 1/2/3-4 maps to ERROR/WARNING/INFO for presentation
only, not a new SpotBugs detector threshold. Original SpotBugs processing
errors prohibit COMPLETE, and the original detector, suppressions and
SpotBugs Maven/Gradle tasks remain authoritative.

## Optional incremental analysis comparison

An existing `m3ReviewAnalysis` task may compare a **separately pinned
baseline** with its current report. This is a linear, no-source-read merge
over the canonical sorted findings. No new analyzer process is started.
Provide the original plugin/goal/version producer keys for both reports;
they must match exactly. The v1 wire report does not contain a producer
certificate, so these strings are operator claims, not authenticated
provenance. The task verifies each supplied report's SHA-256 independently.

```groovy
tasks.named('m3ReviewAnalysis') {
    reportFile.set(layout.projectDirectory.file('analysis.tsv'))
    expectedSha256.set(providers.gradleProperty('m3.analysis.sha256'))
    baselineReportFile.set(layout.projectDirectory.file('baseline.tsv'))
    baselineSha256.set(providers.gradleProperty('m3.baseline.sha256'))
    baselineProducer.set('org.apache.maven.plugins:maven-pmd-plugin:3.28:check')
    currentProducer.set('org.apache.maven.plugins:maven-pmd-plugin:3.28:check')
    failOnIntroducedErrors.set(false) // opt-in; default false
}
```

With both reports explicitly supplied, Gradle logs unchanged, introduced,
resolved and newly introduced ERROR counts. A changed message, location,
rule or severity is a new and resolved finding, not a guessed identity match.
Incomplete analyzer status is clearly reported as *non-comparable*; when
`failOnIntroducedErrors=true`, incomplete status or an absent baseline
fails closed. Unrelated producer keys and malformed baseline bytes are
refused before a change decision. Report contents are not reinterpreted as
a compiler execution decision or build-cache admission. No `check` task
dependency or report baseline is added by default.

## Atomization and patternization

- Maven: authenticate receipt, sort expected producers, append bounded results,
  parse framed rows, decode declared status and normalize individual findings.
- Gradle: a standalone v1 codec applies the same framing, severity, path,
  line/column, ordering and Base64URL checks; a separate task owns Gradle
  task inputs, digest pinning, optional native Problems presentation and gates.
- Neither runtime embeds the other, and neither overrides existing analyzer
  tasks or source rewrite behavior.

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
- No Gradle task-cache hits, Gradle task skipping, deletion of originals, patch
  application, output restoration or automatic model downloads are authorized
  by this bridge. Only explicit Maven polyglot `incremental` mode may skip
  Maven-owned manifest tasks after its declared-input/output checks; it never
  authorizes Gradle's native `build` or `check` tasks to be skipped.
- CodeQL CLI licensing and supported language extractors must be checked for the
  intended use; `hsoliwal/codeql` query sources do not bundle its CLI engine.
- OpenRewrite supports particular source and configuration formats; other
  languages need the appropriate provider rather than a fictitious universal AST.

The PR is opt-in and should stay draft until the actual TestKit test and external
CLI acceptance matrix passes; a standalone Java argument-vector smoke test is
not full Gradle qualification.


## Original Maven Checkstyle/PMD/SpotBugs XML to shared native analyzer reports

The opt-in Gradle task **m3NormalizeAnalyzerXml** reuses the standalone
Java 17 JAR built at hsoliwal/maven/tools/m3-analyzer-report-adapter.
Gradle never embeds Maven Core or copies the converter implementation.
The task receives original Checkstyle/PMD/SpotBugs XML **after** the analyzer's own
original goal/Gradle task runs, checks the converter JAR against an explicitly
configured SHA-256, and invokes the converter as a direct argument-vector
Java process with no shell. Its outputs are M3-STATIC-ANALYSIS-V1 bytes and
a matching digest sidecar under the project build directory.

~~~groovy
plugins { id 'io.synexia.m3.quality' }

tasks.named('m3NormalizeAnalyzerXml') {
    adapterJar.set(layout.projectDirectory.file('vendor/m3-analyzer-report-adapter.jar'))
    adapterSha256.set(providers.gradleProperty('m3.adapter.sha256'))
    originalXml.set(layout.projectDirectory.file('build/reports/checkstyle/main.xml'))
    format.set('CHECKSTYLE')  // or PMD or SPOTBUGS
    originalStatus.set('PARTIAL') // default until original producer attests COMPLETE
}
~~~

If a consumer **explicitly** wants to review the normalized output
in the same Gradle invocation, it may wire task providers to the original
review task. This is not installed by applying the plugin:

~~~groovy
def normalized = tasks.named('m3NormalizeAnalyzerXml')
tasks.named('m3ReviewAnalysis') {
    dependsOn(normalized)
    reportFile.set(normalized.flatMap { it.normalizedReport })
    expectedSha256.set(providers.fileContents(
            normalized.flatMap { it.normalizedSha256 }).asText.map { it.trim() })
    publishProblems.set(false)
    failOnReportedErrors.set(false)
}
~~~

The original Checkstyle/PMD execution task remains independent: users must
explicitly add an appropriate original-producer prerequisite if desired.
The normalized digest is content custody, not analyzer correctness proof.

The adapter JAR must be built, reviewed and supplied explicitly by the
operator. Neither Maven nor Gradle downloads, installs, starts or configures
Checkstyle, PMD or SpotBugs as a side effect of this task. The original result files,
rule sets, suppression rules, plugin configuration, task dependencies and
failure policies remain with their original owners. No dependency is added
automatically from the original analyzer task or from Gradle's check task.
If a producer wants to associate execution with normalized output, it must
wire the explicit task dependency itself.

Use the generated SHA-256 sidecar to configure a subsequent explicit
m3ReviewAnalysis task. Do not treat the digest or the user-provided COMPLETE
status as a trusted proof of source completeness. The normalized XML schema
is documented in the Maven adapter's README and the existing native
M3-STATIC-ANALYSIS documentation.

This task is @UntrackedTask: the installed adapter, original analyzer's
rule set and complete source inventory have not been proven to be eligible
for Gradle build-cache replay. SHA-256 is only a binary/byte-custody check.
Existing binary and semantic compatibility gates are still required.

## Optional incremental analysis comparison

An existing `m3ReviewAnalysis` task may compare a **separately pinned
baseline** with its current report. This is a linear, no-source-read merge
over the canonical sorted findings. No new analyzer process is started.
Provide the original plugin/goal/version producer keys for both reports;
they must match exactly. The v1 wire report does not contain a producer
certificate, so these strings are operator claims, not authenticated
provenance. The task verifies each supplied report's SHA-256 independently.

```groovy
tasks.named('m3ReviewAnalysis') {
    reportFile.set(layout.projectDirectory.file('analysis.tsv'))
    expectedSha256.set(providers.gradleProperty('m3.analysis.sha256'))
    baselineReportFile.set(layout.projectDirectory.file('baseline.tsv'))
    baselineSha256.set(providers.gradleProperty('m3.baseline.sha256'))
    baselineProducer.set('org.apache.maven.plugins:maven-pmd-plugin:3.28:check')
    currentProducer.set('org.apache.maven.plugins:maven-pmd-plugin:3.28:check')
    failOnIntroducedErrors.set(false) // opt-in; default false
}
```

With both reports explicitly supplied, Gradle logs unchanged, introduced,
resolved and newly introduced ERROR counts. A changed message, location,
rule or severity is a new and resolved finding, not a guessed identity match.
Incomplete analyzer status is clearly reported as *non-comparable*; when
`failOnIntroducedErrors=true`, incomplete status or an absent baseline
fails closed. Unrelated producer keys and malformed baseline bytes are
refused before a change decision. Report contents are not reinterpreted as
a compiler execution decision or build-cache admission. No `check` task
dependency or report baseline is added by default.



## PMD CPD duplicate-code report import

The original Maven analyzer converter also supports CPD, including the
current PMD namespaced pmd-cpd report and the legacy unnamespaced
xmlold renderer. Gradle does **not** implement or copy a CPD parser:
the existing opt-in m3NormalizeAnalyzerXml task invokes the same
SHA-256-pinned external Maven adapter JAR with format set to CPD.

    tasks.named('m3NormalizeAnalyzerXml') {
        format.set('CPD')
        originalStatus.set('PARTIAL') // original CPD owner must attest COMPLETE
        originalXml.set(layout.projectDirectory.file('target/cpd.xml'))
    }

Every original reported duplicate location becomes one M3 v1
finding with its source path and line/column. The original CPD group
size in lines/tokens and a canonical group SHA-256 are retained
without copying the potentially sensitive source code fragment.
WARNING is an adapter **presentation-only** severity; the original
CPD minimum token threshold, duplicate detection policy and plugin
exit status remain authoritative. Recoverable CPD processing errors
forbid an adapter status of COMPLETE.

The original CPD Maven/Gradle plugin is never started by merely
applying this bridge. Neither normalizing the report nor reporting
new findings can skip compilation or authorize build-cache reuse.
Optional Problems API display and baseline comparisons use the
existing native Gradle tasks and original v1 byte format.


## CodeQL and other original SARIF 2.1.0 reports

The existing m3NormalizeAnalyzerXml task also accepts format SARIF, using the
**same separately built, SHA-256-pinned Maven adapter JAR**. The original
m3CodeqlSarif task remains the owner of executing CodeQL and producing SARIF.
It is not automatically invoked by applying the quality bridge.

An operator may explicitly connect the existing CodeQL task's output provider
to the normalization task:

    def originalCodeql = tasks.named('m3CodeqlSarif')
    tasks.named('m3NormalizeAnalyzerXml') {
        dependsOn(originalCodeql)
        adapterJar.set(layout.projectDirectory.file(
                'vendor/m3-analyzer-report-adapter.jar'))
        adapterSha256.set(providers.gradleProperty('m3.adapter.sha256'))
        originalReport.set(originalCodeql.flatMap { it.sarifReport })
        format.set('SARIF')
        originalStatus.set('PARTIAL')
    }

originalReport is an additive format-neutral alias of the existing originalXml
file property; existing Checkstyle/PMD/SpotBugs/CPD callers are unchanged.
A producer must explicitly attest COMPLETE from its actual original
execution and source/rule inventory; a well-formed SARIF file alone never
establishes this. The separately published normalized digest can be used
by the existing m3ReviewAnalysis task.

The Maven-owned SARIF 2.1.0 adapter supports original CodeQL-style
tool.driver names, ruleId, result severity, messages, project-relative file
locations, artifact indexes and originalUriBaseIds chains. It is **not** a
full SARIF validator, and it does not import source snippets or automatically
rewrite files. Unsupported/malformed source URI schemes and out-of-project
references are refused. Original source failures cannot be turned into
complete, clean analysis merely by the normalization step.

The converter includes relocated Apache-2.0 Jackson streaming code inside
its standalone JAR; neither Maven Core nor Gradle loads or manages Jackson
as a new build-system dependency. Rebuild and re-pin the JAR SHA-256 after
upgrading the converter. CodeQL CLI/query-pack licensing and the original
analyzer invocation remain separate. No cache replay or compiler skipping
is implied.


## Original Semgrep CLI JSON → native Maven/Gradle diagnostics

The existing opt-in m3NormalizeAnalyzerXml task also accepts **SEMGREP**
as an original report format and delegates to the **same reviewed,
SHA-256-pinned Maven Java converter JAR** already used for XML and SARIF.
This does not create a Semgrep parser or engine inside Gradle. The
original Semgrep CLI, local rule configuration, exit policy, nosemgrep
ignore markers and scan coverage remain the source of truth.

When an original m3SemgrepScan has already emitted its JSON report,
normalization may be requested explicitly:

    tasks.named('m3NormalizeAnalyzerXml') {
        adapterJar.set(layout.projectDirectory.file(
                'vendor/m3-analyzer-report-adapter.jar'))
        adapterSha256.set(providers.gradleProperty('m3.adapter.sha256'))
        originalReport.set(layout.buildDirectory.file(
                'reports/m3/semgrep/results.json'))
        format.set('SEMGREP')
        originalStatus.set('PARTIAL')
    }

The original m3SemgrepScan does **not** run merely because the converter
task exists. Its original output path is provider-backed. Operators may
explicitly wire a dependency on the original task if they want both in
one invocation, but the original Semgrep scan can fail under its own
existing policy when findings exist; in that case Gradle intentionally
does not run dependent tasks as though the scan succeeded. An already
produced report can still be imported in a separate invocation, with
the actual original status declared accordingly.

The original result fields check_id, path, start line/column, severity
and message are converted to existing M3 v1 findings. Rule severity
mapping is **presentation-only**: CRITICAL/HIGH/ERROR -> ERROR,
MEDIUM/WARNING -> WARNING, LOW/INFO/INVENTORY/EXPERIMENT -> INFO.
Explicitly ignored matches remain excluded. Source snippets,
autofixes, metavariables, traces and secret content are not copied
into the normalized report.

Nonempty original errors arrays forbid claiming COMPLETE; a partial
or failed source scan is represented with an explicit project-wide
Semgrep.ScanError finding. A matching hash attests bytes and the
installed adapter binary, **not** actual Semgrep execution or full
rule/source coverage. Existing m3ReviewAnalysis and its opt-in baseline
comparison can consume the generated v1 report as before. Neither
build cache nor compilation/test skipping is authorized by this
normalization. The original Semgrep binary/license stays external.


## Source-pinned cross-build Semgrep proof

The focused Gradle CI workflow now includes a **real Maven-to-Gradle
integration test**, in addition to the original immutable-argv and
incorrect-SHA refusal tests. The workflow checks out Maven fork commit
89de67b38249ea914ef252b765e59d2002780e1b, builds the original
tools/m3-analyzer-report-adapter Java 17 plugin/shaded converter, and
passes that exact JAR to a Gradle TestKit fixture on both JDK 17 and 21.
No engine is copied into the Gradle fork.

The TestKit fixture creates an original Semgrep-style JSON report,
pins the *actual JAR bytes* with SHA-256, invokes the existing
m3NormalizeAnalyzerXml task using its format-neutral originalReport
input, and verifies the canonical M3 v1 path/rule/severity, output
SHA-256 and absence of source snippets. No original Semgrep CLI is
executed by the fixture: the purpose is to verify that the exact
external Maven converter works as a Gradle-owned native task.

A normal local Gradle test run skips only this external-binary
integration case when M3_ADAPTER_JAR is not explicitly supplied; the
original unit tests and TestKit refusal cases still execute. The
dedicated GitHub matrix job explicitly builds and supplies the JAR
so that the case is not skipped there. Source ownership, tool/rule
completeness and production analyzer parity require separate tests;
passing a JAR smoke never authorizes Gradle build cache reuse.
