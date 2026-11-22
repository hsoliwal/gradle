<!-- SPDX-License-Identifier: Apache-2.0 -->
# Original Maven #246 current-nine Gradle project-DB acceptance

## Historical source and current contract

- [Merged Maven #246](https://github.com/hsoliwal/maven/pull/246) at immutable commit `5942487fbfa7ad7fbc7313cecf12958f823b6262` upgraded its original Java 17 `M3GradleTestKitReceiptMain` to a **separate** `current-nine` mode. The original six-case and one-case symlink/pruning modes remain unchanged for historical reproducibility.
- The current Gradle project-DB bridge `master@82b6cd32df2af82f1f2e253e0c8b5dac501f8af4` contains exactly nine source test cases: one `M3ProjectDbRealEngineTest`, one `M3ProjectDbGradleNativeRealEngineTest`, six `M3ProjectDbTaskTest` (including binary API/configuration-cache tests), and one `M3ProjectDbPrunedSymlinkRealEngineTest`. Their method identifiers match Maven #246's exact secure parser. The source count/IDs were inspected; **execution of this new lane remains pending**.
- The existing `.github/workflows/m3-gradle-maven224-projectdb-junit.yml` continues to pin merged Maven #230 for the original **six-case** historical contract. Its reports must not be relabeled as nine-case qualification.

## Additive Gradle source-pinned acceptance

`.github/workflows/m3-gradle-maven246-projectdb-nine.yml` independently checks out exact merged Maven #246. It confirms the source Git object IDs of Maven's secure Java 17 parser, unchanged shell wrapper and Java17 negative/mode test runner; executes the original `verify-gradle-junit-modes.sh` before building engines; builds the original Maven polyglot module state and project-DB JARs and runs their source-owned verification.

It then executes exactly one Gradle TestKit `test` invocation per JDK (Temurin 17/21), selecting the four original Gradle JUnit classes explicitly with `--rerun-tasks`. `M3_PROJECT_DB_JAR`, `M3_POLYGLOT_JAR`, `M3_PRUNED_PROJECT_DB_JAR` and `M3_PRUNED_POLYGLOT_JAR` reference the **same** newly source-built Maven artifacts in that job. It calls **Maven's original secure Java17 receipt verifier** with `current-nine` against the actual generated Gradle JUnit XML. No Gradle-local XML parser, data store, dependency graph or cache hit is introduced.

The original parser enforces exactly the nine specified named direct-child cases across four suites, zero skips/failures/errors, bounded regular no-follow JUnit XML, and no extra project-DB suites. It rejects forged XML/DTD and count mismatch, rather than accepting merely a green Gradle process exit. JUnit XML and HTML reports are retained even if CI fails.

The source already provides an opt-in OpenRewrite consumer recipe `io.synexia.gradle.recipe.EnableM3QualityBridge`. This batch reuses it; no recipe or plugin changes, build task attachment or data reimplementation occur.

## Qualification boundaries

The **existing Maven-owned** project DB remains the authority for module discovery, provenance, indexes, BM25 CPU-only retrieval and M3IndexDB snapshot contents. Gradle only calls the original pinned CLI and runs its native task graph. The nine-case gate is not a claim that configuration/build-cache correctness, arbitrary Maven goals or native OS/JNI databases have been exhaustively proven. A passing source parser unit test is not a positive TestKit artifact.

New Gradle workflow Java17/21, original Maven `mvn install/verify`, produced JUnit artifact inspection, Windows/macOS, authentic M3JDK21 runtime, cross-process crash/failure and OpenRewrite SDK replay remain **NOT RUN** until exact-head hosted outcomes are inspected. Human DCO and provenance rights review remain independent gates. Keep this batch draft.

Audit scope: specific Maven #184/#190/#202/#208/#215/#218/#224/#226/#230/#234/#239/#246 and Gradle #37/#46/#52/#57/#58/#61/#62 branches/PRs at the default-head checkpoint above, **not** a claim of completed all-ref/full-history recovery.
