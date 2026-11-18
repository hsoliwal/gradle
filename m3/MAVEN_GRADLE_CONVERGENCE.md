<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven / Gradle M3 convergence register

Source review: 2026-11-18. Owners: `hsoliwal/maven` and `hsoliwal/gradle`.
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
| ECJ XML and javac diagnostic reports | **Maven #160 and #178 merged** | Opt-in `m3NormalizeCompilerDiagnostics` and original pinned Maven `NormalizeCli` | Retain Maven's original parsers; refuse non-English or truncated javac claims of COMPLETE; neither compiler runs on import |
| IDE delta exchange and environment inputs | Maven #78, #82, #92, #106 | Gradle #25 and **#33 merged** | Gradle InputChanges owns native deltas; v1 digest-pinned export/review only, no build skip |
| Universal native language task graph / SPI | Maven #115, #120, #132 | Gradle #24, #25 | Reuse Maven's pinned CLI and provider JARs for manifest tasks; leave Gradle's original task model untouched |
| Rebar3/Erlang and Composer/PHP native phases | **Maven #212 merged** (`95bc17f8212350af1632e16990e985f8a2ee72cb`) | Original Mavenizer → `m3-build.tsv` → existing opt-in Gradle `m3PolyglotPlan`; independent source-pinned JDK17/21 real TestKit lane in this change | Mavenizer owns command detection, POM/profile and manifest generation; Gradle plans the same manifest without running native tools, inventing Composer install, or duplicating phase rules |
| Per-module content-verified incremental state | **Maven #143, #144–#146, #157 merged** | Gradle #28, #30, #31 merged | Forward only the qualified Maven CLI selections; do not copy lifecycle goals or assume Gradle cache hits |
| Portable module-state backends and whole-run locks | **Maven #147 merged**, later **#184** one-owner consolidation | Gradle #30–#31 merged | Preserve Maven-owned module leases, transaction generations and storage; Gradle does not supply JDBC/native drivers automatically |
| Current M3JDK database platform binding | **Maven #220 merged** (`c19789716fab23db8b46180f9fd8982bdff298d0`) | Explicit `m3DbModuleAccess` selector on `m3PolyglotRun`; independent Java 17 command contract and Maven #220 source-pinned JDK17/21 real-engine refusal CI | Current `jdk.m3.db.M3DB` / legacy `jdk.m3.indexdb.M3IndexDB` remain M3JDK-owned; Gradle offers four fixed JVM module-visibility choices, never installs engines, forks database code or assumes cache equivalence |
| M3IndexDB unified per-project state, native Gradle JUnit evidence and RAG | **Maven #184, #186–#188, #190–#196 merged** | Gradle #37 and #41 merged | Maven reads native Gradle JUnit XML from projects without POMs; original IndexStore retains one storage owner; dry-run #194 fix source-pinned, no Gradle task/cache/ABI ingestion or duplicated parser |
| Persistent syntax/atom fingerprints | **Maven #148 merged** in smart-builder | Gradle #32 merged, `m3MachineFingerprints` CLI proxy | Maven/JDK AST, canonical receipt validation and native MIndexDB own syntax facts; Gradle supplies a pinned task boundary only |
| Maven polyglot artifact restore cache | **Maven #149 merged** (after #155 plugin repair) | Gradle #34–#35 merged | Explicit original Maven cache import only; preserve Gradle task/cache semantics and refuse implicit enablement |
| Resource filtering API | **Maven #150 merged** | Gradle's Copy/Sync/filter/task inputs are native | Adapt capabilities only if semantics differ and tests justify additive APIs |
| Code clustering and LSP front ends | Maven #113, #117, #124, #142, #156, #168 | Gradle #36 merged for Eclipse builder registry | Future shared M3AST/LSP artifacts; do not start language servers automatically or duplicate Eclipse models |
| Generic Eclipse language-builder SPI | **Maven #189, #192, #197 merged** | **Gradle #36, #40, #42 merged** | Original Eclipse source owner, ServiceLoader, selected configuration and same-invocation handoff wire remain authoritative; no builder execution or cache skip from advisory facts |
| Bounded Eclipse/PDE and polyglot authoring descriptors | **Maven #211 merged** (`2b4ea06f34d785b78a7811ca59cf6d24efb02287`) | Gradle #47 `m3EclipseModel` original-engine TestKit; independent JDK17/21 source-pinned CI lane in Gradle #51 | Original Maven owns manifest, plugin/fragment, `*.target`, `*.product`, native build descriptor discovery and SHA-256; Gradle does not clone parser, skip builds or follow symlinks |
| LLVM original compiler toolchain SPI | **Maven #198, #199, #213, #217, #219 merged**; #214 closed without merge | Gradle #44/#48 original provider proof; #50 open for Maven #210 executable-byte evidence | Original Maven provider chooses clang/clang++/flang/llc and later LLVM-home aliases; old #198 source pin does not prove newer alias semantics, native binaries or cache equivalence |
| Optional native LLVM/MLIR/LLD pipeline provider | **Maven #203 merged** (`3441dc1e91a2835b7b6bca608ffb9676565d9eff`) | Gradle's v1 inventory validator now admits Maven's appended `assembler`, `linker`, `optimizer` slugs; independent Java17 contract, source-pinned original provider JAR, TestKit and JDK17/21 CI | The original Maven SPI/ServiceLoader owns `opt`, `mlir-opt`, `llvm-as`, `llvm-link`, `mlir-translate`, LLD, lit and FileCheck; SHA-256 pin both JARs; Gradle never forks tool dispatch, state or LLVM parsers |
| RocksDB, MapDB and LMDB optional module stores | **Maven #206 and follow-up #209 merged** | Existing Gradle `ext-*` selector plus #206 exact-source negative-runtime TestKit lane | Maven's sealed module state, leases, ServiceLoader and missing-driver refusals remain authoritative; newer #209 builder-return behavior and actual native-driver cache/recovery need distinct qualification |
| Browser AI review integration | Maven #138–#139 remove prior Chrome agent wiring | None here | Respect removed code; do not restore abandoned hooks |
| Reproducible original LLVM executable evidence | **Maven #210 merged** (`78848ef9a52fbc1ae34032bd46b3d057b5f1d7fd`) after Maven #203 | New opt-in `m3LlvmToolchainScan` / `m3LlvmToolchainVerify` read the original separately SHA-256-pinned Maven provider JAR and run its standalone CLI; exact-source JDK17/21 CI lane | Maven owns `M3-LLVM-TOOLCHAIN-V1`, symlink resolution, executable SHA-256, atomic publication and refusal; Gradle does not rehash/reinterpret manifests or cache native outputs |
| Optional transaction-backed external stores | **Maven #206 and #209 merged** | Existing external `ext-*` provider-JAR bridge is still opt-in; real RocksDB/MapDB/LMDB Gradle runtime qualification pending | Import only source-qualified provider artifacts; #209's returned builder paths must be included, not copied as Gradle storage code |
| PDE / polyglot descriptor facts | **Maven #211 merged** | Earlier Gradle Eclipse model and builder handoff tasks remain independent; separate descriptor import qualification pending | Maven/Eclipse source models retain descriptor ownership; do not invent a duplicate Gradle parser |

Maven-specific behavior (Resolver, enforcer, Mavenizer POM generation,
Maven core lifecycle participation and Maven API services) remains in Maven.
Gradle-specific behavior (configuration cache, build cache, incremental task
execution, artifact transforms, variant selection, Worker API and Problems API)
remains in Gradle. M3JDK21 is the eventual shared owner for reusable facts,
state fingerprints, language structures and transformations where a compatible
versioned contract is available.

## Maven #220 current M3JDK database module-access boundary

The merged Maven #220 source at
`c19789716fab23db8b46180f9fd8982bdff298d0` prefers the current
M3JDK-owned `jdk.m3.db.M3DB`, retains legacy
`jdk.m3.indexdb.M3IndexDB`, and refuses `m3db`/`native` storage when
neither a visible platform class nor a reviewed installed donor runtime
is present. Its original `IndexStores` and `DirectoryStateBackend`
remain the only implementation of the database binding and backend.

Gradle's existing `m3PolyglotRun` now offers an **explicit**
`m3DbModuleAccess` property, default `none`. Fixed choices are
`current-java-base`, `current-named`, `legacy-java-base` and
`legacy-named`; these emit only the corresponding `--add-exports` and,
for named modules, `--add-modules` JVM arguments ahead of the existing
Maven polyglot CLI. Any other value is refused. An export requires
`mode=run`, `incremental=true` and `stateBackend=m3db` or `native`;
planning, other backends and the original no-export command remain
unchanged. The operator must separately supply and digest-pin the
original Maven #220 engine and explicitly select an appropriate M3JDK
Java executable. Gradle installs no JDK or native database.

An offline Java 17 command-contract proof checks all selections and
rejections. The separate `.github/workflows/m3-gradle-m3jdk-db-module-access.yml`
checks out exactly merged Maven #220, runs Maven's original `verify`
and exercises the actual Java 17/21 Gradle TestKit launcher. On stock
JDKs it **must fail closed** for absent M3JDK native storage, not
create `.m3/maven-build-db` or execute user tasks. A positive
patched-M3JDK/named-module runtime deployment remains a separate
qualification gate; CI does not imply it was installed.

## Maven #212 native Rebar3 and Composer manifest boundary

The **merged** Maven #212 source
`95bc17f8212350af1632e16990e985f8a2ee72cb` adds original Mavenizer
Erlang and PHP detection. The Maven fork decides which `rebar.config`,
`test/`, `composer.json` and PHPUnit descriptors justify native phase
entries in `m3-build.tsv`. It generates `rebar3 compile`/`eunit` and
`composer validate --no-check-publish`/`exec phpunit` only when their
original conditions apply. It neither infers Composer installation nor
performs native execution while planning or generating files.

The Gradle fork retains its already merged, original-
`PolyglotCli`-delegating `m3PolyglotPlan` and `m3PolyglotRun`. A new
real-engine TestKit case loads Mavenizer from the separately pinned JAR,
checks its no-write plan and emitted native manifest, then passes that
unchanged manifest to the existing Gradle *plan-only* task. Independent
CI builds the exact Maven #212 producer and runs this cross-repository
acceptance on Java 17 and 21. No new Gradle parser or native tool driver
is created. This is a build-plan contract, **not** a compiler/toolchain,
deployment, Maven reactor or Gradle build-cache equivalence proof.

The recipe at `m3/quality-bridge/recipes/enable-m3-native-manifest-bridge.yml`
only enables the plugin in an explicitly selected consumer; Mavenizer
generation and native execution remain separate opt-ins. Hosted CI and
independent recipe replay must qualify the proof before promotion.

## Maven #211 Eclipse descriptor qualification boundary

Maven #211 extends the original `EclipseProjectModel` to inspect bounded
Eclipse/PDE/OSGi and native-language authoring metadata without reading
generated output directories, traversing arbitrary source trees or
following symlinks. Gradle #47 already calls the original Maven
`EclipseModelCli` from the opt-in `m3EclipseModel` task and has an optional
positive TestKit acceptance. The additive cross-fork lane introduced in
Gradle #51
pins Maven's **merged** #211 source at
`2b4ea06f34d785b78a7811ca59cf6d24efb02287`, executes Maven's original
`verify`, and runs Gradle's real-engine TestKit on Java 17 and 21. The
original Maven implementation must refuse 129 immediate `*.target` files
and symlinked `plugin.xml` rather than publishing successful model evidence.

These facts are **diagnostic**; they are not effective compiler inputs,
resolved target platform, proven native toolchain, cache validity or
compilation authorization. The OpenRewrite install recipe remains opt-in,
and Maven/Gradle ownership boundaries, public APIs and default lifecycle
behavior remain unchanged. Hosted CI outcomes and independent recipe
replay, rather than the existence of test code, decide qualification.

## Imported contract from merged Maven #143, #147 and later M3DB reconciliation

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

The Gradle bridge validates backend names using Maven #147's published
grammar; the Maven engine owns driver discovery, module-wide run locks,
cache image formats, validation, revocation and commit. Extra driver/provider
JARs are explicit, digest-pinned `providerJars`. A missing one fails closed.

The Gradle task stays explicitly untracked: it is not intrinsically
cacheable, and no implicit attachment to `build` or `check` exists.
Existing command overloads and plugin tasks are preserved.

Maven's later merged #184 adds explicit `embedded` and `native` spellings,
which the merged Gradle #39 forwards to the original Maven CLI. `legacy` is
the compatibility alias of the embedded M3IndexDB archive; `native` is the
alias of `m3db`'s directory store. The older #149 CI remains source-pinned
for independent cache compatibility, while a separate Maven #188 pin and
real Gradle TestKit check the newer aliases and invalidation semantics.

## Imported original Maven #160 and #178 compiler-diagnostic boundary

The additive, explicitly invoked `m3NormalizeCompilerDiagnostics` task uses
the existing Gradle `M3NormalizeAnalyzerXmlTask` and source-pinned Maven
`NormalizeCli`. Only two new format literals (`ECJ`, `JAVAC`) enter the
no-shell argument contract; the existing task's properties, default
Checkstyle mode and output files remain unchanged. The distinct new task
defaults to `JAVAC`, `PARTIAL` and `compiler-analysis.tsv/.sha256`.

Maven #160 owns ECJ XML and javac diagnostic parsing, source-relative
locations, sorted M3-STATIC-ANALYSIS-V1 rows and byte receipts. Follow-up
#178 **must be included** because its unreadable/non-English javac refusal
prevents an invalid empty `COMPLETE` report. CI pins original Maven source
`26efa14266288f8cb11c6232bbdc8ea94ae26134`, runs its own adapter tests,
then runs real Gradle TestKit against the actual Maven JAR and original ECJ
3.45.0 and javac 21.0.9 fixtures (including a German javac refusal).
No compiler or static analyzer is run by import, and producer-reported
completeness is not independently authenticated. The new opt-in OpenRewrite
consumer recipe requires SDK replay before promotion.

## Imported original Maven #148 AST/atom fingerprint boundary

`m3MachineFingerprints` is an opt-in `@UntrackedTask` registered separately
from Gradle compilation, `check` and `build`. It uses only the original Maven
`io.synexia.maven.smart.MachineFingerprintMain` and source-pinned
`m3-smart-maven-plugin` JAR, not a second Gradle parser, semantic ledger,
M3DB file format or source transformation. A user must supply the original
SHA-256-pinned JAR, the existing Java-only source root, and the canonical
OpenRewrite preparation and atom/pattern receipts. The Maven CLI checks
their exact content and current source-bound provenance before and after
computation; Gradle performs only file-scope and SHA-256 admission checks.

`workers` is 1..8; `attributeTypes` is optional compiler attribution;
`analyzeFlow` requires attribution and promises only may-flow evidence, not
equivalence proof. Maven's `moduleDb=auto|off|required` is preserved and
native MIndexDB remains at `.m3/maven/mindexdb`. Any external provider and
its transitive classpath must be separately installed and SHA-256 pinned.
The Maven CLI produces `build/m3-smart/machine-evidence.tsv` and
`machine-fingerprints.json`, including explicit refusal receipts. The
Java 17 Gradle bridge can be configured without a Java21 runtime, but the
original producer is executed only using a configured Java21 executable.

New proof cases: pure Java17 immutable argv/refusal checks, TestKit against
a fake original CLI for full option/path transfer and preflight failures,
and a gated TestKit exercise of the *real* Maven JDK21 producer refusing
unbound receipts. Maven's own #148 tests cover valid canonical receipts,
deterministic workers and local MIndexDB facts independently. Further
qualification requires TestKit with true canonical Synexia OpenRewrite
receipts, actual native MIndexDB warm/cold recovery on multiple platforms,
source mutation/cancellation/crash tests, and a verified recipe replay.

### Proof matrix

- Java argument-vector tests: preserve legacy signatures; forward the merged
  portable modes; reject malformed, unknown, non-run and nonincremental selection
- Gradle TestKit with fake engine: verify exact literal argv, project
  property isolation, no-shell handoff and early refusal
- Gradle TestKit with real Maven #147 engine: persist state across processes,
  reject output tampering, detect same-size/restored-mtime source changes,
  reject malformed state and refuse missing SQLite/H2 drivers and external
  providers without starting a language tool
- Gradle CI: checkout the exact Maven merged cache commit containing #143,
  #147 and #149, `b47e9a1e9173502c19de9bb6657a4f88b3267487`, build its original
  standalone Maven plugin, run Maven's own portable DB contract tests, then
  run cross-build Gradle TestKit on JDK 17 and 21
- Required beyond this PR: actual optional native MIndexDB runtime acceptance,
  actual JDBC/provider round trips from a pinned Gradle classpath,
  cross-process lock and crash tests on Windows/Linux, full Gradle distribution
  test, real canonical OpenRewrite-to-Gradle #148 positive replay and native
  M3IndexDB acceptance of the newer #184–#188 generations

No test listed here is claimed to have passed solely because it was authored.
Source replay, build logs, commit hashes and CI artifacts establish acceptance.

## Maven #210: immutable LLVM toolchain evidence without native execution

Maven #210, merged at `78848ef9a52fbc1ae34032bd46b3d057b5f1d7fd`,
extends the **separately installed** `m3-llvm-tools-provider` JAR with the
original `io.synexia.maven.llvm.LlvmToolchainEvidence` CLI. Its `scan` and
`verify` commands own a sorted, root-relative, byte-digested
`M3-LLVM-TOOLCHAIN-V1` manifest; they do not execute LLVM binaries or claim
build provenance from the reviewed LLVM source revision alone.

The Gradle fork now exposes separate, lazy `@UntrackedTask` boundaries:
`m3LlvmToolchainScan` publishes an operator-selected report under its project
build directory using the Maven CLI's atomic implementation;
`m3LlvmToolchainVerify` rechecks the original manifest against current
source-owned installed executable bytes. Both require an explicit `llvmHome`
and **SHA-256-pinned original Maven provider JAR**. No embedded LLVM scanner,
hash format, symlink resolver, parser, cache, or duplicate ServiceLoader was
introduced; malformed paths and missing/changed JARs fail closed before the
original CLI is called.

`scripts/verify-llvm-evidence-args.sh` exercises a strict Java 17 no-shell
argv/refusal contract without Gradle. The independent
`.github/workflows/m3-gradle-llvm-evidence.yml` JDK17/21 matrix checks out
the exact Maven #210 merged revision, verifies its original JDK17 contracts,
and invokes Gradle TestKit against the actual provider JAR. A synthetic
executable file is used **only as binary-byte evidence**, never run as LLVM:
positive scan and verify, changed-file refusal, and incorrect provider-JAR
SHA refusal are covered. It does not establish ABI/native correctness,
Gradle build-cache eligibility, a user-built LLVM fork, or multi-OS parity.

Source-owned, optional OpenRewrite consumer recipe:
`m3/quality-bridge/recipes/enable-m3-llvm-evidence-bridge.yml`.

## Maven #203: additive LLVM/MLIR/LLD pipeline SPI vocabulary

Maven #203 adds `LINKER`, `OPTIMIZER` and `ASSEMBLER` to its original v1
`M3ToolCapability` enum and packages `m3-llvm-tools-provider` as a **separate**
optional JAR. Earlier Gradle inventory admission rejected those three
perfectly valid lowercase capability slugs, even when Maven's engine and
provider JARs were both explicitly SHA-256 pinned. The Gradle bridge now
accepts the complete eight-value original Maven vocabulary while retaining
the original v1 header, four-field rows, ascending unique provider IDs,
sorted unique capability/language tokens, canonical LF UTF-8 and size caps.
Unknown future capabilities still fail closed pending a protocol review.

No second LLVM driver, ServiceLoader, grammar, parser or scheduler exists in
Gradle. `m3PolyglotProviders`, `m3PolyglotPlan` and `m3PolyglotRun` use the
already established **separately** pinned `providerJars` and
`providerJarSha256` configuration. No `opt`/`llvm-as`/`llvm-link` invocation
occurs on plugin application, `providers` or `plan`; `run` remains explicit
and `@UntrackedTask`. Original Maven's native-tool tasks are excluded from
generic incremental/restore shortcuts until full toolchain/ABI/dependency
evidence is qualified.

The Java 17 standalone protocol contract is executable without Gradle, and
TestKit fixtures run against original Maven source pinned to the **merged**
#203 commit `3441dc1e91a2835b7b6bca608ffb9676565d9eff` (independent of
the earlier #198 compiler-only lane). Tests require both original artifacts:
`m3-polyglot-maven-plugin` and the separately built `m3-llvm-tools-provider`.
They verify actual ServiceLoader inventory, the three-stage LLVM IR plan
without native execution, absence/refusal when the provider is missing, and
rejection of a corrupt JAR digest. Maven's own Java 17 contract covers binary
selector failures and argv forwarding. Hosted CI and actual toolchain builds
must establish release readiness; LLVM source revision
`591f3d1edf0a9ee718d17a0ee70dd013b3c01908` was reviewed but is not
silently installed or built by Gradle. Source-owned OpenRewrite opt-in
recipe: `m3/quality-bridge/recipes/enable-m3-llvm-provider-bridge.yml`.
