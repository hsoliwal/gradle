<!-- SPDX-License-Identifier: Apache-2.0 -->
# Optional Maven #149 polyglot cache from Gradle

Source owner: hsoliwal/maven, merged PR #149
(commit b47e9a1e9173502c19de9bb6657a4f88b3267487, incorporating the
Maven #155 plugin descriptor and Java warnings repair).
Receiver: hsoliwal/gradle, optional m3/quality-bridge plugin.

This is **not Gradle's native build cache**. Gradle remains the authority for
task graph, configuration cache, incremental compilation, local/remote build
caching, toolchains, variants and Worker API. The Maven polyglot engine owns
only explicitly selected *manifest* tasks. The bridge does not implement a
second Gradle cache or replace existing Gradle task types.

## Enable explicitly

Install/build the reviewed Maven polyglot JAR, compute its original SHA-256,
and select the existing engineJar/engineSha256 properties. Then:

~~~groovy
plugins { id 'io.synexia.m3.quality' }

tasks.named('m3PolyglotRun') {
    // Required: directory already exists and is outside the project checkout.
    localArtifactCacheDirectory.fileValue(file('/reviewed/local/m3-polyglot-cache'))
    // Optional, independent of output restoration:
    incremental.set(true)
    stateBackend.set('file')
}
~~~

Invoke m3PolyglotRun explicitly. No plugin task is attached automatically to
build/check or runs simply when the plugin is applied. There is no default
cache folder or implicit download. The CLI appends the literal argument pair
--build-cache <absolute-directory> only when the option is set. Plan mode
rejects it. The Gradle task validates the selected cache as an existing,
non-symlinked directory outside its declared workspace; the Java argument
builder validates that cache selection is run-only.

The cache works with or without the Maven incremental state option. If both
are configured, the original Maven engine decides whether to report
UP-TO-DATE, FROM-CACHE or EXECUTED and owns the resulting receipts. Gradle's
m3PolyglotRun remains @UntrackedTask and cannot take a Gradle native
FROM-CACHE result from these receipts.

## Correctness and limitations

**Only enable for an explicitly audited, hermetic manifest task.** Maven
#149's cache key currently includes the declared source/input content stamp,
task arguments and declared outputs, plus host OS and architecture. It does
**not prove** all compiler versions, environment variables, toolchain bytes,
dynamic dependencies, generated input roots, external services, locale,
classpath, ABI or runtime context. A successful cache hit may therefore be
semantically incorrect for an incompletely declared task.

The Gradle bridge does not improve those keys. It deliberately does not
enable a cache automatically, does not copy Maven cache logic into Gradle,
and does not claim that the Maven cache supplies source/binary/behavioral
equivalence. CodeQL, OpenRewrite, and unqualified M3 provider tasks remain
excluded from cache admission. The original default policy continues to exclude
all provider tasks; the explicit provider-reuse option described below adds a
separate Maven-owned qualification path. The engine hashes
stored blobs on reuse and rejects malformed or mismatched entries, but
that establishes only stored-content integrity, not correct build context.

The caller must independently pin the Maven engine JAR, toolchain, versions
and every relevant external input before permitting reuse. If those
conditions cannot be satisfied, leave this option unset and use the native
Gradle mechanisms where they are applicable.

## Qualification commands

~~~sh
sh m3/quality-bridge/scripts/verify-polyglot-cache-args.sh
./gradlew -p m3/quality-bridge test
~~~

The standalone JDK 17 test checks original Java overloads, run-only
admission, the immutable argument vector, mode and backend combinations.
Gradle TestKit checks that an explicitly selected cache directory is
forwarded and that a cache under the project directory or one configured
for planning fails before any Maven engine process is started.

The full acceptance matrix still needs the exact reviewed Maven engine built
from #149, an external temporary cache, two independent workspaces,
cache hit, changed source input miss, damaged blob refusal and cross-platform
runs. In particular, a synthetic fake CLI TestKit test is an argument-boundary
check, not a proof that Maven's real cache is correct or safe for arbitrary
language tools.

## Explicit provider-qualified reuse

`providerReuse` defaults to `false`. With a reviewed Maven engine that supports
`--provider-reuse`, the existing `m3PolyglotRun` task can request the original
provider's eligibility check:

```groovy
tasks.named('m3PolyglotRun') {
    incremental.set(true)
    stateBackend.set('file')
    providerReuse.set(true)
}
```

Alternatively, select an existing external `localArtifactCacheDirectory` with
`providerReuse.set(true)` to request output restoration without enabling
incremental state. The option requires run mode and at least one of those
reuse mechanisms. Planning or a stateless run with this option fails before
starting the Maven CLI. Every existing Java command overload and every task's
default argument vector remain unchanged when the property is false.

The bridge forwards the literal flag to the same pinned Maven engine. Maven's
provider contract owns the toolchain/context identity, declared input closure,
output ownership and final decision. A request does not make an unqualified
provider cacheable: ordinary execution remains the fallback. The initial
qualified built-in path is the deliberately restricted explicit JDK compiler
invocation accepted by the source-owned provider. LLVM, dynamic dependencies,
processors and arbitrary external providers receive no new reuse permission
from the Gradle flag.

The initial JDK contract permits verified reuse and output restoration only in
the same workspace. Its cache identity includes that workspace; a different
checkout compiles again. It requires explicit UTF-8 sources, disabled annotation
processing and implicit class generation, explicit `-Xprefer:source` selection,
declared directory-only classpaths and sourcepaths, and one owned output directory
matching the compiler's `-d` option. Source preference is required because javac's
default source/class choice can depend on timestamps even when contents match.
JAR classpaths, injected JVM/compiler options, unknown compiler flags, symlinked
toolchains and an incomplete input declaration do not qualify. Maven verifies
the supported JDK's toolchain contents as additional inputs on each reuse check.

`m3PolyglotRun` remains an untracked Gradle task. A Maven `UP-TO-DATE` or
`FROM-CACHE` message describes a selected manifest action only; it does not
admit Gradle-native `JavaCompile`, task-cache or configuration-cache reuse.
The old engine JAR must be upgraded and its SHA-256 re-pinned before selecting
the new option. An old CLI that rejects the flag fails normally.

The existing argument contract and TestKit boundary fixtures cover unchanged
defaults, explicit forwarding and invalid combinations. The separate
`M3PolyglotProviderReuseRealEngineTest` requires `M3_PROVIDER_REUSE_JAR` naming
the source-built engine with this feature. It exercises the original JDK
provider through the actual Gradle task, including warm reuse, same-workspace
removed-output restoration and changed-source invalidation. Supplying an older engine through
`M3_POLYGLOT_JAR` does not silently select this newer acceptance test.
