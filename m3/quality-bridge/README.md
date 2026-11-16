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
- No cache hits, goal skipping, deletion of originals, patch application, output
  restoration or automatic model downloads are authorized.
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
