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
    // Only if incremental is true: "legacy" (default), "file", "m3db",
    // "sqlite", "h2", or an explicitly installed "ext-*" backend.
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

### Original Maven #198 LLVM compiler provider, available from Gradle

Merged [Maven #198](https://github.com/hsoliwal/maven/pull/198) introduces the
opt-in `@m3:llvm:compiler:` provider through the **existing** Maven-owned
`M3ToolProvider` SPI. Gradle needs no second LLVM parser, driver, scheduler,
dependency graph, atomizer or toolchain installation: its existing
`m3PolyglotProviders`, `m3PolyglotPlan` and `m3PolyglotRun` delegate to the
same source-pinned `PolyglotCli` with an externally installed original LLVM
compiler. The language identifiers are `c`, `cpp`, `objc`, `objcpp`,
`fortran` and `llvm-ir`, with original drivers `clang`, `clang++`, `flang`
and `llc` selected by Maven—not Gradle.

For example, supply the verified newer Maven polyglot JAR in the common
configuration above, and place this in an explicit `m3-build.tsv`:

```text
M3-POLYGLOT-TASKS-V1
task	compile	@m3:llvm:compiler:c	.
arg	compile	--m3-llvm-bin=/opt/llvm-reviewed/bin
arg	compile	-c
arg	compile	hello.c
arg	compile	-o
arg	compile	hello.o
input	compile	hello.c
output	compile	hello.o
```

`./gradlew m3PolyglotPlan` must leave the compiler unexecuted, even when
the selected binary is not installed; only a deliberate
`./gradlew m3PolyglotRun` executes Clang. The `--m3-llvm-bin=` selector is
consumed by Maven's original provider and must occur at most once; no shell
is involved. The Maven CLI JAR is SHA-256 pinned; Gradle validates the returned
provider inventory against the original versioned schema.
No LLVM source/binaries are vendored into this fork; the
[`hsoliwal/llvm-project`](https://github.com/hsoliwal/llvm-project)
source revision reviewed by Maven is `591f3d1edf0a9ee718d17a0ee70dd013b3c01908`
(Apache-2.0 WITH LLVM-exception). A Clang installed on another host is
**not** thereby proven to be built from that revision.

Gradle's cross-fork CI adds a source-pinned Maven #198 provider build and
real Gradle TestKit using Maven's exact source-pinned `hello.c` and two-stage
Clang/`llc` manifest: inventory contains LLVM, dry-run planning refuses
duplicate selectors without starting a tool, and an explicitly located,
SHA-256-observed **host** Clang emits LLVM IR twice. Re-execution
is intentional: native headers, sysroots, ABI, response files and generated
inputs do not yet form a complete M3DB cache key. CI smoke does **not**
qualify the user's own LLVM fork binary, Flang, `llc`, Objective-C,
cross-compilation, linking, LLVM/MLIR passes or Windows/macOS. Follow-up
[Maven #199](https://github.com/hsoliwal/maven/pull/199) is still an
independent draft for shared LLVM-home and binary-pin selection; do not
infer that its unmerged behavior exists in Maven #198. The opt-in
[OpenRewrite consumer recipe](recipes/enable-m3-llvm-provider-bridge.yml)
installs the plugin only and requires SDK replay before promotion.

### Maven #203: separately installed LLVM/MLIR/LLD pipeline provider

Merged [Maven #203](https://github.com/hsoliwal/maven/pull/203) adds native
`assembler`, `linker` and `optimizer` capability slugs to the *same* original
provider SPI, plus an optional artifact
`io.synexia:m3-llvm-tools-provider:0.1.0-SNAPSHOT`. Its original
`ServiceLoader` implementation forwards arguments to `opt`, `mlir-opt`,
`llvm-as`, `llvm-link`, `mlir-translate`, LLD drivers, `llvm-lit` and
`FileCheck`. Gradle owns none of those tools or their cache identity.

Build the **two original Maven JARs** from source pinned to merged commit
`3441dc1e91a2835b7b6bca608ffb9676565d9eff`, and configure the existing
tasks with *independent* content digests. For example, after placing the
verified original artifacts in `vendor`:

```groovy
plugins { id 'io.synexia.m3.quality' }

['m3PolyglotProviders', 'm3PolyglotPlan', 'm3PolyglotRun'].each { id ->
    tasks.named(id) {
        engineJar.set(layout.projectDirectory.file('vendor/maven-polyglot.jar'))
        engineSha256.set(providers.gradleProperty('m3.llvm.engine.sha256'))
        providerJars.from(layout.projectDirectory.file('vendor/llvm-tools.jar'))
        providerJarSha256.put('llvm-tools.jar',
            providers.gradleProperty('m3.llvm.tools.sha256'))
    }
}
tasks.named('m3PolyglotPlan') { target.set('link') }
tasks.named('m3PolyglotRun')  { target.set('link') }
```

Supply the exact original Maven #203
[`example/llvm-ir/m3-build.tsv`](https://github.com/hsoliwal/maven/blob/3441dc1e91a2835b7b6bca608ffb9676565d9eff/tools/m3-llvm-tools-provider/example/llvm-ir/m3-build.tsv)
and `hello.ll`. `./gradlew m3PolyglotProviders` inventories the external
provider; `./gradlew m3PolyglotPlan` shows its `opt -> llvm-as -> llvm-link`
pipeline without running it. Only an explicit `m3PolyglotRun` requests
native execution. Maven's optional `--m3-llvm-bin=`, `--m3-llvm-home=` and
`--m3-llvm-sha256=` selectors remain Maven-owned; a selected missing binary
fails planning without silently falling back to PATH. Native toolchain,
ABI, pass plugins, sysroot, output state and Gradle build-cache hits are
**not** inferred from the provider inventory or a successful plan.

The Gradle side has a standalone Java 17 schema contract at
`scripts/verify-llvm-tools-inventory.sh` and an independent JDK17/21
source-pinned TestKit lane. This is compatibility with Maven's merged SPI,
not a claim that the reviewed LLVM fork was built or that LLVM execution is
qualified on every platform. The existing
[OpenRewrite opt-in recipe](recipes/enable-m3-llvm-provider-bridge.yml)
applies to both LLVM adapters, and no build task is wired automatically.

### Maven PRs #143 and #147: opt-in portable module-local state

Maven [PR #143](https://github.com/hsoliwal/maven/pull/143) has merged the
initial portable CLI contract. Maven [PR #147](https://github.com/hsoliwal/maven/pull/147)
subsequently merged `sqlite`, `h2` and explicitly installed `ext-*` backend
selection. The Gradle fork
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
    // or 'sqlite', 'h2', or 'ext-operator-store' with the separately installed,
    // reviewed driver/provider JARs in the digest-pinned classpath
}
```

Alternatively `-Pm3.polyglot.stateBackend=file`, `m3db`, `sqlite`, `h2` or
`ext-operator-store` configures the
`m3PolyglotRun` task only. `m3PolyglotPlan` always defaults to `legacy`
even when the project property is set, so a plan remains read-only.
The existing signatures and default argv remain unchanged. A nonlegacy
backend on a nonincremental run, an unknown backend, or a nonlegacy backend
configured explicitly for planning is rejected *before* launching the engine.
Every nonlegacy mode requires an independently reviewed Maven engine JAR
with the corresponding CLI capability; the existing SHA-256 engine pin is
mandatory. For SQLite and H2, supply the appropriate JDBC driver as a
separately pinned `providerJars` entry (with its exact filename-to-SHA-256
entry in `providerJarSha256`). The same mechanism admits a trusted external
`M3ModuleDbBackendProvider` service JAR for `ext-*`. Missing drivers,
providers, duplicate JAR names or mismatched pins fail rather than falling
back to another backend. The bridge does not accept arbitrary JDBC URLs or
`derby`, which is not advertised by Maven's merged portable CLI. No database
driver, MIndexDB native runtime, or backend is **bundled or downloaded**.

Maven's standalone engine verifies declared output contents for reuse and
conservatively rehashes where ctime/file-key evidence is unavailable.
Maven #147 now introduces module-wide execution locking and transactional
storage choices; those guarantees belong to the Maven engine and require
cross-process, native and filesystem-specific qualification. The subsequent
merged #144–#146 designs were reconciled by Maven #184 into one owning DB;
this Gradle task never copies their layouts. Maven [#148](https://github.com/hsoliwal/maven/pull/148)
merged native MIndexDB-backed syntax fingerprint persistence for its own
smart-builder goal; this Gradle task does not imply that the separate goal
or its native runtime is installed. Unsupported future backend names fail
closed, and no Gradle task/output-cache hit is asserted by the M3 state.

#### Maven #184: explicit M3IndexDB state-backend aliases

The separately merged [Maven #184](https://github.com/hsoliwal/maven/pull/184)
unifies canonical per-module state. The same Maven-owned Java polyglot CLI accepts
two additional literal selectors: `embedded` (the original `.m3/module.m3db`
archive; `legacy` aliases it), and `native` (the original
`.m3/maven-build-db` directory store; `m3db` aliases it). Gradle now forwards
either exact spelling **only for an explicitly incremental `m3PolyglotRun`**:

```groovy
tasks.named('m3PolyglotRun') {
    incremental.set(true)
    stateBackend.set('embedded') // alias 'legacy'; Maven-owned archive
    // stateBackend.set('native') // alias 'm3db'; Maven-owned directory
}
```

The default remains `legacy` for existing Gradle consumers, with unchanged
argv. These two additional selectors require a **newer source-pinned Maven
engine**, not the older pinned #149 binary. CI builds the original Maven
polyglot JAR from `f92096f8d3eb135d28cf698335a4423ef9179e9d`, verifies
the donor's own tests, then checks both alias pairs through real Gradle
TestKit invocations: persistent generation reuse, separate archive/directory
shapes and output-tamper invalidation. These imports never claim Gradle
configuration-cache or task output-cache equivalence. The opt-in
[OpenRewrite consumer recipe](recipes/enable-m3-polyglot-state-bridge.yml)
adds only plugin availability; it is a reviewed-replay gate, not an automatic
state migration or a compiler execution recipe.

### Maven PR #148: opt-in original JDK 21 AST/atom fingerprints

The `m3MachineFingerprints` task is a separate, **explicit** delegation to
`io.synexia.maven.smart.MachineFingerprintMain` from the original
[`hsoliwal/maven/tools/m3-smart-builder`](https://github.com/hsoliwal/maven/tree/master/tools/m3-smart-builder).
Build the source-pinned `m3-smart-maven-plugin-0.1.0-SNAPSHOT.jar` using a JDK 21
Maven toolchain, compute its SHA-256, and provide current original OpenRewrite
preparation and atom/pattern receipts. Those receipts are authoritative only
after the **Maven** producer's strict, source-bound checks; a locally supplied
file or an engine SHA-256 alone never proves that recipes ran.

```groovy
plugins { id 'io.synexia.m3.quality' }

tasks.named('m3MachineFingerprints') {
    engineJar.set(layout.projectDirectory.file('vendor/m3-smart-maven-plugin.jar'))
    engineSha256.set(providers.gradleProperty('m3.fingerprints.sha256'))
    sourceRoot.set(layout.projectDirectory.dir('src/main/java'))
    preparationReport.set(layout.projectDirectory.file('receipts/preparation.json'))
    atomPatternManifest.set(layout.projectDirectory.file('receipts/atom-pattern.tsv'))
    workers.set(4)            // bounded 1..8; deterministic per-file fan-in
    attributeTypes.set(false) // JDK21 compiler type/call attribution is opt-in
    analyzeFlow.set(false)    // requires attributeTypes=true; may-flow is not proof
    moduleDb.set('auto')      // 'off' or 'required' also supported
}
```

Run `./gradlew m3MachineFingerprints` only after those two source-bound
receipts have been generated by their original, approved producers. Point
`-Pm3.fingerprints.java` at a **Java 21** executable if the Gradle daemon uses
another JDK. The original producer alone writes
`build/m3-smart/machine-evidence.tsv` and `machine-fingerprints.json`, with
structural hash, normalized logic cues, SimHash, syntactic contract facts,
optional compiler attribution and bounded may-flow analysis. These are
evidence and pattern *candidates*, not extracted private methods, equivalent
programs or recipe-promotion authority.

For persisted syntax results, the original CLI independently supports
`--module-db auto|off|required`. `auto` uses an already installed native
`com.synexia.mindex.db.MIndexDB` provider or computes normally; `off` forbids
DB use; `required` refuses when no qualified provider is installed. The
canonical store remains at `.m3/maven/mindexdb` with its original sealed
artifacts and lock. Supply the complete, independently SHA-256-pinned native
provider classpath as `providerJars`/`providerJarSha256`, exactly as for the
polyglot SPI. No MIndexDB format, parser, source-fingerprint row or cache is
copied into Gradle. The task is intentionally `@UntrackedTask`; no Gradle
up-to-date or build-cache decision is derived from these receipts. No native
provider is bundled or downloaded. See the [Maven native module DB contract](https://github.com/hsoliwal/maven/blob/master/tools/m3-smart-builder/README.md).

Preflight checks refuse linked/out-of-scope paths, missing or mismatched
engine/provider digests, invalid DB modes, and flow without attribution.
The original producer additionally verifies the evidence manifests and
source inventory before/after computation and can publish an explicit
`refused` receipt. Neither this task nor its registration is attached to
`compileJava`, `check` or `build` automatically. The Java 17 Gradle plugin
remains usable without running the Java 21 producer.

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

## Generic Eclipse language builders (JDT, CDT, RustDT, Corrosion, PyDev, PDE, LSP4E, future plugins)

`m3EclipseBuilders` is an opt-in, read-only companion to `m3EclipseModel`.
Both tasks delegate to the Maven-authored `EclipseModelCli`, without a second
parser, Eclipse OSGi runtime or compiler in Gradle. The new report uses
`m3-eclipse-builder-owners-v1` and preserves original builder order,
exact extension IDs, registered adapter IDs, unbound natures and descriptor
hashes. The Maven source owns the versioned `M3EclipseBuilderProvider`
ServiceLoader interface. Unknown builders remain **unbound**, rather than
being assigned to javac, clang, cargo or a guessed build command.

```groovy
plugins { id 'io.synexia.m3.quality' }

tasks.named('m3EclipseBuilders') {
    engineJar.set(layout.projectDirectory.file('vendor/m3-polyglot-maven-plugin.jar'))
    engineSha256.set(providers.gradleProperty('m3.polyglot.sha256'))
    eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))

    // Optional installed Eclipse builder descriptors; every JAR is separately pinned.
    // providerJars.from(layout.projectDirectory.file('vendor/eclipse-adapter.jar'))
    // providerJarSha256.put('eclipse-adapter.jar',
    //     providers.gradleProperty('m3.eclipse.adapter.sha256'))
}
```

Invoke `./gradlew m3EclipseBuilders` explicitly to produce
`build/reports/m3/eclipse-builders.json`. Merely applying the plugin does
not execute this task, invoke Eclipse builders, start LSPs, install toolchains,
resolve CDT build configurations or import Maven projects. The same strict
checksum validator used by the existing polyglot adapter verifies supplied
engine and provider JARs. The output is versioned JSON, atomically published
only after successful CLI completion; it does not authorize task/cache skipping.

Maven now reads **immediate** arbitrary `.settings/` files (bounded and
without following symlinks) so vendor-specific Eclipse language metadata is
visible in either model. Nested Eclipse workspaces, actual `IResourceDelta`,
JDT working copies, `IBuildConfiguration`, CDT `ICBuildConfiguration`,
Rust/Cargo execution, markers and cancellation still belong to Eclipse or
their installed owners. Later qualified IDE plugins should exchange those
real facts through the existing M3 IDE delta wire instead of fabricating them.

### Per-configuration Eclipse build evidence

A separate \`m3EclipseBuildContext\` task now reviews an explicit packet
written by the original Eclipse/m2e owner. It invokes the **same pinned**
Maven \`EclipseModelCli context <packet>\` reader and writes
\`build/reports/m3/eclipse-build-context.json\` with schema
\`m3-eclipse-build-context-v1\`. It is never attached to the regular
Gradle \`build\`, \`compileJava\`, or \`check\` tasks.

\`\`\`groovy
tasks.named('m3EclipseBuildContext') {
    engineJar.set(layout.projectDirectory.file('vendor/m3-polyglot-maven-plugin.jar'))
    engineSha256.set(providers.gradleProperty('m3.polyglot.sha256'))
    eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))
    contextPacket.set(layout.projectDirectory.file(
        'eclipse-project/target/ide/build-context.ide'))
}
\`\`\`

The parent \`target/ide\` directory and the packet must be supplied explicitly
by the Eclipse owner (see \`hsoliwal/m2e-core\` PR #18). Gradle checks that the
packet exists **inside** the selected Eclipse project, is a regular no-follow
file, and is bounded to 1 MiB. It also checks the original Maven engine JAR
and any separately provided adapter JARs against their required SHA-256 pins.
Maven's original parser validates strict canonical UTF-8, records, identities
and configuration consistency; Gradle does **not** fork the format parser.

The packet distinguishes the **selected** \`IBuildConfiguration\` from the
currently active one, retaining actual named and default-empty configurations
and the original builder/nature order. It is read-only metadata. It does not
enable Gradle task skipping, M3DB output reuse, live Eclipse JDT/CDT/Rust
compilation, or replace the IDE's native progress/cancellation.

### Eclipse single-invocation handoff (any language plugin)

The opt-in \`m3EclipseBuildHandoff\` task consumes the Maven-authored Java
17 \`EclipseModelCli handoff\` command through the **same pinned engine JAR**.
It never parses Eclipse resources independently or starts JDT, CDT,
Corrosion/Cargo, PyDev, PDE, Tycho or any language server. The original
m2e builder may produce a \`M3-ECLIPSE-BUILD-HANDOFF-V1\` packet, only under
its existing capture switch, which binds the actual selected
\`IBuildConfiguration\` to the source-owner \`IResourceDelta\` from one
m2e invocation.

\`\`\`groovy
plugins { id 'io.synexia.m3.quality' }

tasks.named('m3EclipseBuildHandoff') {
    engineJar.set(layout.projectDirectory.file('vendor/m3-polyglot-maven-plugin.jar'))
    engineSha256.set(providers.gradleProperty('m3.polyglot.sha256'))
    eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))
    handoffPacket.set(layout.projectDirectory.file(
        'eclipse-project/target/ide/build-handoff.ide'))
}
\`\`\`

Run \`./gradlew m3EclipseBuildHandoff\` **only after the source owner has
explicitly exported that packet**. The task refuses a missing, symlinked,
out-of-project or over-budget packet before executing the pinned Maven
reader. The Maven decoder validates its content. Gradle verifies the
distinct \`m3-eclipse-build-handoff-v1\` JSON schema and atomically writes
\`build/reports/m3/eclipse-build-handoff.json\`, up to 24 MiB. Existing
\`m3EclipseModel\`, \`m3EclipseBuilders\` and \`m3EclipseBuildContext\`
tasks are unchanged.

The handoff's UUID distinguishes invocations. Its separate stable,
workspace-scoped configuration identity can be derived by the Maven
codec but is **not** a complete Gradle cache key or source generation.
Unknown plugin natures and builders remain source-owned, and the output
never authorizes goal skipping, Gradle task outcome reuse, compiler
substitution, or M3DB artifact restoration. Actual input/output and
toolchain eligibility must be qualified in the appropriate original
Eclipse and language-specific build owners.

### Reconcile current Eclipse declarations against the original invocation

\`m3EclipseReconcile\` is another **explicit**, untracked Gradle task that
delegates to the original Maven Java 17 \`EclipseModelCli reconcile\`
subcommand. It compares the current \`.project\` declaration with one
Eclipse-owned, invocation-bound handoff packet. Gradle does not parse or
reinterpret the builder IDs, natures or resource deltas itself.

\`\`\`groovy
tasks.named('m3EclipseReconcile') {
    engineJar.set(layout.projectDirectory.file('vendor/m3-polyglot-maven-plugin.jar'))
    engineSha256.set(providers.gradleProperty('m3.polyglot.sha256'))
    eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))
    handoffPacket.set(layout.projectDirectory.file(
        'eclipse-project/target/ide/build-handoff.ide'))
}
\`\`\`

The output \`build/reports/m3/eclipse-reconciliation.json\` has the
distinct schema \`m3-eclipse-reconciliation-v1\`. \`RECONCILE\` is required
if the project ID, builder sequence, or nature sequence changed; a
FULL/CLEAN invocation or an incomplete original delta also requires
reconciliation. \`DECLARATIONS_MATCH\` only verifies that *these*
declarations agree; it never authorizes Gradle up-to-date decisions,
task skipping, local/remote cache replay, or a second compiler.

It reuses the existing SHA-256-pinned engine/provider JAR classpath,
project-local packet check, safe output destination, bounded UTF-8
capture and atomic report publication. JDT, CDT, RustDT/Corrosion,
PDE, PyDev, Xtext and other Eclipse plugins still own their live
language and build state.

### Native Eclipse/Polyglot descriptor acceptance

The optional `M3EclipseNativeDescriptorsRealEngineTest` uses the same pinned
Maven engine artifact as `m3EclipseModel`; it does not embed an Eclipse
parser, compiler or resource watcher in Gradle. It checks Maven's
`m3-eclipse-project-v1` output for original PDE/OSGi manifests and
`*.target` files, CDT/CMake, Rust/Cargo, Swift, Python and Node metadata,
including exact SHA-256 byte custody. It checks that generated output trees
are excluded, changing a PDE target changes its digest, and no source is
rewritten. Existing m2e, Gradle and Maven lifecycle contracts are untouched.

To run the real cross-repository acceptance after building/reviewing Maven's
`m3-polyglot-maven-plugin` JAR, set `M3_POLYGLOT_JAR` to its absolute path
and execute:

```sh
./gradlew -p m3/quality-bridge test
```

The ordinary offline TestKit suite skips this external-JAR integration if
`M3_POLYGLOT_JAR` is absent; it is **not** a passing acceptance test in
that case. The Maven JAR must incorporate the source-owned language
descriptor changes in Maven PR #211. No task is automatically bound to
`build`, `check` or `compileJava`, and source hashes alone never
authorize cache restoration.

The independent GitHub Actions lane
`.github/workflows/m3-gradle-eclipse-descriptors.yml` checks out **exactly**
merged Maven #211 (`2b4ea06f34d785b78a7811ca59cf6d24efb02287`),
builds and verifies its original polyglot JAR, and invokes these TestKit
checks with `M3_POLYGLOT_JAR` set on **both Java 17 and 21**. Alongside the
positive native-descriptor SHA-256/change test, the cross-fork acceptance
checks Maven's original bounded `*.target`/`*.product` admission (129
descriptors refused) and rejection of a symlinked `plugin.xml` without
reading its external contents. The shell guard
`m3/quality-bridge/scripts/verify-eclipse-descriptor-workflow.sh` refuses
the accidental removal of the exact producer revision or real-engine
TestKit selector. This is a CI specification, **not** evidence of a
passing hosted run before the workflow has executed.

For recipe-first adoption, the additive, opt-in OpenRewrite recipe
`m3/quality-bridge/recipes/enable-m3-eclipse-descriptor-bridge.yml`
only installs the quality plugin. A consumer still configures the original
Maven engine JAR and digest and explicitly calls `m3EclipseModel`; the
recipe does not attempt to parse descriptors or register a source mutation.

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

### Maven #160 and #178: original ECJ and javac compiler diagnostics

The **additional** opt-in `m3NormalizeCompilerDiagnostics` Gradle task runs the
same digest-pinned `io.synexia.maven.analysis.NormalizeCli` as
`m3NormalizeAnalyzerXml`. It defaults to `JAVAC`, `PARTIAL` and distinct
`build/reports/m3/import/compiler-analysis.{tsv,sha256}` outputs. Select `ECJ`
for an existing Eclipse Compiler for Java XML `-log` report. The legacy task,
property names, default Checkstyle format and output locations are unchanged.

```groovy
plugins { id 'io.synexia.m3.quality' }

tasks.named('m3NormalizeCompilerDiagnostics') {
    adapterJar.set(layout.projectDirectory.file('vendor/m3-analyzer-report-adapter.jar'))
    adapterSha256.set(providers.gradleProperty('m3.adapter.sha256'))
    originalReport.set(layout.projectDirectory.file('build/reports/compiler/javac-utf8.log'))
    format.set('JAVAC')       // or 'ECJ' for ECJ's existing XML -log file
    originalStatus.set('PARTIAL') // COMPLETE only if the original compiler attests it
}
```

Run `./gradlew m3NormalizeCompilerDiagnostics` explicitly. The task does not
invoke or replace Gradle `JavaCompile`, the original Eclipse JDT/ECJ compiler,
`javac`, the original source analysis, or their toolchains and plugins. ECJ's
log uses source-bound `ecj.<problem-id>` rules and preserves its absent column
as zero. The Java 21 javac format uses file/line/column when reported, produces
`javac.<lint-key>` warning rules, and maps `Note:` messages to INFO; both
normalize to M3-STATIC-ANALYSIS-V1. The original Maven parser owns the complete
grammar and secure XML handling. No second parser is introduced in Gradle.

For a report that may claim `COMPLETE`, capture the original javac diagnostic
stream in **English, UTF-8**, e.g. with
`-J-Duser.language=en -J-Dstderr.encoding=UTF-8`. The corrected Maven
[#178](https://github.com/hsoliwal/maven/pull/178) adapter refuses a
`COMPLETE` claim on unreadable/non-English lines; it also rejects truncated
"only showing the first N errors" summaries as complete. ECJ's original
DOCTYPE is accepted without loading it, while foreign DOCTYPE/entity inputs
are refused. A genuine compiler crash must remain `FAILED` or `PARTIAL`.

Existing `m3ReviewAnalysis` may consume this task's two output providers
only when explicitly wired, just like the Checkstyle/PMD task. The SHA-256
sidecar proves byte custody, not source completeness or compiler success.
The source-pinned [OpenRewrite opt-in plugin recipe](recipes/enable-m3-quality-bridge.yml)
can make the bridge available to a consumer build; activation and actual
compiler-log selection remain separate. It has not been replayed through
an OpenRewrite SDK in this environment and is a promotion gate, not a proof.

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
26efa14266288f8cb11c6232bbdc8ea94ae26134 (including the #178
non-English javac refusal fix), builds the original
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

The same pinned Maven source supplies genuine ECJ 3.45.0 and javac 21.0.9
test fixtures. An additional Gradle TestKit lane invokes the original
adapter for both and verifies their common file/line/severity facts, seven
findings (two errors and five warnings), and matching SHA-256 sidecars.
Another test refuses a German javac log as `COMPLETE` before any success
report is published. These cases require both `M3_ADAPTER_JAR` and
`M3_ADAPTER_COMPILER_FIXTURES` locally; GitHub CI supplies the source-pinned
binary and inputs. No live compiler execution is claimed by a report-import
test.

## Native Gradle IDE deltas for Maven and other hosts

This fork now provides the separate opt-in m3IdeDeltaExport and
m3IdeDeltaReview tasks. Export uses Gradle's original InputChanges and
emits the canonical Maven M3-IDE-DELTA-V1 envelope plus a SHA-256 sidecar.
Import requires separately supplied, exact source bytes and SHA-256,
and checks owner/project identity before presenting change claims.
Neither task is connected to build/check or allowed to cause any compiler,
test or build-cache skip. Gradle retains its native scheduler and change
history; Maven retains its original M3ExternalBuildService classification.

See [the source-bound IDE delta contract](docs/M3-IDE-DELTA.md) for
configuration, examples, strict wire bounds, provenance and qualification.

## Opt-in Maven #149 local cache for explicit polyglot targets

The existing `m3PolyglotRun` task now accepts an optional
`localArtifactCacheDirectory` property. It forwards Maven's original
`--build-cache <absolute-directory>` CLI argument, with no Gradle-side
cache implementation, only for explicitly invoked run tasks. Cache
configuration is absent by default; an existing non-symlink directory
outside the checkout must be supplied. Existing command APIs and default
execution remain unchanged.

This does **not** use or authorize Gradle's own build cache, and does not
make arbitrary polyglot tasks hermetic. Maven #149's cache key does not
prove toolchain, environment or undeclared inputs; only audited native
tool tasks should opt in. See
[the experimental polyglot-cache contract](docs/M3-POLYGLOT-CACHE.md)
for examples, limitations and acceptance gates.

## Per-project M3IndexDB and CPU-only build-evidence retrieval

Maven #184 and merged #190 provide one M3IndexDB/IndexStore project
database for polyglot receipts, Surefire/Failsafe results, dependency
trees, POM evidence, build logs, code-clustering facts and native Gradle
JUnit reports under build/test-results. Gradle-only modules are discovered
from build/settings descriptors, without a POM. The Gradle fork now offers
two **opt-in, untracked** tasks which delegate to the **original, separately
SHA-256-pinned Maven ProjectDbCli** and its polyglot storage-engine JAR:

- m3ProjectDbIngest: ingest a Maven project's original local build evidence
  into an ignored project database, writing an ingest JSON receipt
- m3ProjectDbQuery: query the existing database using Maven's deterministic
  CPU-only BM25/hash-vector/facet retrieval, writing a JSON report with the
  original per-hit file, line and build id when matching evidence is present

Neither task runs by applying this plugin or joins build/check. Maven
#190 recognizes Gradle JUnit XML test reports with original file/line
provenance; it does **not** import Gradle's task graph, dependency
resolution, compiler outputs or cache generations. No invented build-cache
authorization, source rewrite, LLM-generated answer or database fork is
introduced. The two Maven JARs and optional external providers must be
installed and pinned explicitly. See [Project DB bridge contract](docs/M3-PROJECT-DB.md)
and [qualification receipt](docs/M3-PROJECT-DB-QUALIFICATION-20261118.md).
