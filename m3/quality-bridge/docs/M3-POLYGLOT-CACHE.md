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
equivalence. CodeQL, OpenRewrite, and pluggable M3 provider tasks are not
cache candidates under the original Maven policy. The engine hashes
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
