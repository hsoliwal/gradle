<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven / Gradle M3 convergence register

Source review: 2026-11-17. Owners: `hsoliwal/maven` and `hsoliwal/gradle`.
This is an implementation register, not a claim that every Maven PR is
applicable to Gradle. Preserve both public APIs, JVM compatibility,
original authority for tools, source/license provenance, and native
Gradle task-graph/cache semantics. Do not force Maven's reactor/goal
abstractions into Gradle's providers, variants, task graph or workers.

## Reconciliation against committed Maven history and pull requests

| Capability | Maven source lineage | Gradle native or bridge lineage | Disposition |
| --- | --- | --- | --- |
| JDK-owned M3AST compiler facts | Maven #1, #7, #8 | Gradle #1, #2, #3, #6 | Native compiler option/worker integration exists; independently verify ABI and compilation |
| Dependency sessions, build inputs and toolchain facts | Maven #23, #52, #54, #61–#65 | Gradle #6, #13 plus Gradle's original inputs and dependency APIs | Use native Gradle providers and dependency graph; no Maven API copy |
| CodeQL, OpenRewrite, Checkstyle, PMD, SpotBugs, CPD, SARIF, Semgrep | Maven #72, #74, #76–#99 | Gradle #9–#23 | Existing opt-in pinned analyzer bridge; do not duplicate original parsers |
| IDE delta exchange and environment inputs | Maven #78, #82, #92, #106 | Gradle #25 (Eclipse descriptor model only) | Partial; native Gradle delta/IDE wire integration requires separate source-qualified implementation |
| Universal native language task graph / SPI | Maven #115, #120, #132 | Gradle #24, #25 | Reuse Maven's pinned CLI and provider JARs for manifest tasks; leave Gradle's original task model untouched |
| Per-module content-verified incremental state | **Maven #143 merged** | Gradle #28 bridge, this stacked update | Forward `legacy|file|m3db`; opt-in, fail-closed, `@UntrackedTask`; never claim a native Gradle cache hit |
| Portable module-state backends and whole-run locks | **Maven #147 merged** after #143 | This stacked update | Forward `sqlite|h2|ext-*` to the same pinned Maven CLI; no Gradle-side DB implementation or automatic dependency provision |
| Competing DB store formats | Maven #144–#146 **open** | Not imported | Review independently before any state-format migration or replacement |
| Persistent syntax/atom fingerprints | **Maven #148 merged** in smart-builder | No native Gradle syntax-fingerprint task in this change | Preserve M3JDK/Maven fact ownership; separate source-qualified consumer needed |
| Maven polyglot artifact restore cache | Maven #149 **open** | Gradle already has its native local/remote build cache and snapshot systems | No port of a weaker Maven duplicate; evaluate task contracts and M3DB complementarity separately |
| Resource filtering API | **Maven #150 merged** | Gradle's Copy/Sync/filter/task inputs are native | Adapt capabilities only if semantics differ and tests justify additive APIs |
| Code clustering and LSP front ends | Maven #113, #117, #124, #142 | No claim of native Gradle port | Future opt-in shared M3AST/LSP artifact integration, without running language servers automatically |
| Browser AI review integration | Maven #138–#139 remove prior Chrome agent wiring | None here | Respect removed code; do not restore abandoned hooks |

Maven-specific behavior (Resolver, enforcer, Mavenizer POM generation,
Maven core lifecycle participation and Maven API services) remains in Maven.
Gradle-specific behavior (configuration cache, build cache, incremental task
execution, artifact transforms, variant selection, Worker API and Problems API)
remains in Gradle. M3JDK21 is the eventual shared owner for reusable facts,
state fingerprints, language structures and transformations where a compatible
versioned contract is available.

## Imported contract from merged Maven #143 and #147

The Gradle bridge's `m3PolyglotRun` has an `@Input Property<String> stateBackend`
with default `legacy`. `file` selects one sealed binary image per Maven
manifest workspace; `m3db` selects a separately installed native MIndexDB
runtime. Maven #147 additionally permits `sqlite` and `h2` with independent
JDBC drivers, and `ext-[a-z0-9][a-z0-9-]{0,47}` with an explicitly installed
`M3ModuleDbBackendProvider`. These modes use the original sealed-image
contract and Maven-owned module-wide run locking; Gradle copies none of their
storage logic. Any nonlegacy selection requires `incremental=true` and the
reviewed Maven engine JAR's SHA-256. `m3PolyglotPlan` ignores the run-only
project property and remains read-only. `derby`, arbitrary URLs, malformed
external IDs and unknown modes are rejected by the current Maven CLI contract.

The Gradle task stays explicitly untracked: it is not intrinsically
cacheable, and no implicit attachment to `build` or `check` exists.
Existing command overloads and plugin tasks are preserved.

### Proof matrix

- Java argument-vector tests: preserve legacy signatures; forward the merged
  portable modes; reject malformed, unknown, non-run and nonincremental selection
- Gradle TestKit with fake engine: verify exact literal argv, project
  property isolation, no-shell handoff and early refusal
- Gradle TestKit with real Maven #147 engine: persist state across processes,
  reject output tampering, detect same-size/restored-mtime source changes,
  reject malformed state and refuse missing SQLite/H2 drivers and external
  providers without starting a language tool
- Gradle CI: checkout the exact Maven merged commit containing #143 and #147,
  `4d4976f9cf6f62b5f007cf5ab80ee92c67c0f3f7`, build its original
  standalone Maven plugin, run Maven's own portable DB contract tests, then
  run cross-build Gradle TestKit on JDK 17 and 21
- Required beyond this PR: actual optional native MIndexDB runtime acceptance,
  actual JDBC/provider round trips from a pinned Gradle classpath,
  cross-process lock and crash tests on Windows/Linux, full Gradle distribution
  test, and dedicated Gradle consumption of Maven #148 syntax facts

No test listed here is claimed to have passed solely because it was authored.
Source replay, build logs, commit hashes and CI artifacts establish acceptance.
