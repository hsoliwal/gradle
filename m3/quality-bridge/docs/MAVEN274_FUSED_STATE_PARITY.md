<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven #274 fused input fingerprints → opt-in Gradle cache/state acceptance

## Source and ownership

Original [merged Maven #274](https://github.com/hsoliwal/maven/pull/274)
at immutable merge commit `53d7bd782077968e596a54cb7948ea22810734c3`
added a package-private `InputStamps.StampPair` and `pairedStamps` so
the original Maven TaskRunner observes declared input bytes **once per
phase** when local incremental state and portable output caching both
qualify. It does not merge their key formats, eliminate post-execution
observations, trust mtimes, or authorize new Gradle task-cache skips.

The Gradle `m3PolyglotRun` bridge already accepts explicit
`incremental=true`, Maven `stateBackend='embedded'`, and
`localArtifactCacheDirectory` outside the project. The original Maven
engine JAR is independently SHA-256 pinned; Gradle's
`M3PolyglotManifestTask` remains `@UntrackedTask`, with **no**
`build`/`check`/JavaCompile dependency or Gradle-managed restore.

This batch adds only
`M3FusedStateCacheRealEngineTest` to Gradle and a source-pinned external
CI lane to Maven. The original Maven `BuildCacheStateContractTestMain`
remains the authority for direct-versus-paired digest equality, restored
mtime and changed content, failure/recovery, independent cache mode,
module receipt revocation and provider refusal. Gradle does not fork
`InputStamps`, `TaskRunner`, sealed MIndexDB layout or cache artifacts.

Original Maven's input-stamp engine transcript includes the **absolute JAR code-source path**. The two Gradle TestKit workspaces must therefore reference the very same externally staged original Maven JAR, not duplicate identical JAR bytes at different project-local paths. Otherwise the correctly different engine identities would invalidate the test's expected portable cache key.\n\n## Executable Gradle TestKit contract

A source-built original Maven #274 polyglot engine is supplied through
`M3_FUSED_MAVEN_ENGINE_JAR` with an explicit
`M3_REQUIRE_FUSED_REAL_ENGINE=1` JUnit guard. The Gradle test creates
a Java17 fixture tool and declared `source.txt` → `result.txt` task
in two independent project checkouts, sharing only one separately chosen
Maven portable cache directory. It proves:

1. Original execution creates the Maven-owned portable cache and a local
   sealed `.m3/module.m3db` receipt. A second checkout restores declared
   output bytes without calling the fixture Java tool.
2. Repeating that checkout yields the original **Maven-owned**
   `UP-TO-DATE` state; changed or missing outputs are not trusted.
   Changing input bytes **without changing byte count or mtime** forces
   a new original tool execution and new output.
3. A missing declared input cannot revive an old cache hit.
   Changed Maven engine SHA, a project-local cache, or a symlinked
   cache ancestor must fail before any Maven-owned language tool executes.

All assertions concern original Maven receipts. The test uses
`--rerun-tasks` so a Gradle scheduler skip cannot impersonate Maven
correctness. The same source-built artifact is verified on JDK17/21
through **Maven-owned GitHub Actions** because Gradle's fork Actions
had no corresponding recent runs in the initial audit.

## Hold gates and original runtime boundaries

This document and source code alone are not a CI receipt. The exact
source-pinned Maven real-engine workflow, source/JDK digest verification,
**three executed non-skipped JUnit cases on each JDK**, original Maven
`mvn verify` checks and human DCO/legal review must pass before
promotion. Re-run when the original Maven #274 owner changes.

Maven #275's newer M3File watcher/scan exclusions and pending #278 Core
Checkstyle correction are **separate scope**. This source pin proves
the fused Maven plugin owner but does not certify a fresh full Maven
Core reactor, installed `org.m3jdk.file` native module, full Windows or
macOS filesystem durability, or every cache/provider identity. The
original opt-in `EnableM3PolyglotStateBridge` OpenRewrite recipe is
reused; no new compiler, index, code parser or cache key was created.
