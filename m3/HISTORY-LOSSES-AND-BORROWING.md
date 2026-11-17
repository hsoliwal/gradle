<!--
SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
SPDX-License-Identifier: Apache-2.0
-->

# History losses and borrowing from the Maven fork

This page records an audit of this fork's history, made on 2026-11-17. It covers three things:

- what the fork lost in its own merges and pushes;
- which capabilities upstream Gradle removed (or plans to remove) that are worth keeping reachable as opt-in features;
- what this fork and the Maven fork (`hsoliwal/maven`) can borrow from each other.

Every statement names the commit, file or command that backs it. Work that was not executed is marked **NOT RUN**.

## Scope and method

| Item | Value |
|---|---|
| Fork head | `beabedc9cc4a` (#26) |
| Upstream | `gradle/gradle` master `c2c0eb5a5250` (2026-11-17). GitHub compare: 105 commits ahead, 0 behind. |
| Merges audited | 27 fork merges since 2026-10-01, 0 skipped. Command: `merge_loss_audit.py <clone> c2c0eb5a..HEAD 2026-10-01 <out> --all-merges`. The blobless clone was deepened to 2026-09-01 so that no merge sat on the shallow boundary. |
| Follow-up | `merge_loss_verify.py` checked the findings at the head |
| Branch heads | 38 checked with `git merge-base --is-ancestor` against master |
| Maven side | `hsoliwal/maven` `docs/HISTORY-LOSSES-AND-BORROWING.md` (hsoliwal/maven#151) |

Raw verdicts: `DROPPED_INCOMING` 0, `DROPPED_MAINLINE` 0, `REVERTED_BOTH` 0, `TOOK_INCOMING` 1. Files against upstream: 157 added, 12 modified, 0 deleted, so no upstream file is missing.

## Fork losses

### Real loss: the quality-bridge README section (restored on this branch)

| | |
|---|---|
| Commit | `6370bfd77bbd` ("docs(m3): show optional provider-backed Gradle normalization to review composition"), 2026-11-16 14:17:04 +0530 |
| How it was lost | It was pushed to `m3/original-xml-normalizer-task-20261116` 89 seconds after PR #14 had merged that branch at `193b8bab1afe` (as `57ba1bec82d2`, 14:15:35). It never reached master. |
| What was missing | 11 of its 15 lines of eight or more characters were missing from master's `m3/quality-bridge/README.md` |
| Restore | Cherry-picked onto master as `aab5c4aef3e7` (author and date kept, plus a `cherry picked from` line). `git merge-tree` had shown a clean apply: tree `9b1f74137ab3`, +20 lines. |

Proof (JDK 21, the fork's own wrapper, Gradle 9.9.1):

- A consumer build includes `m3/quality-bridge` and applies the restored snippet verbatim. `verifyReadmeWiring` prints:
  - `dependsOn=[m3NormalizeAnalyzerXml]`
  - `reportFile.present=true`, and `reportFile` equals `m3NormalizeAnalyzerXml.normalizedReport` (`build/reports/m3/import/analysis.tsv`)
  - `publishProblems=false failOnReportedErrors=false`
- `m3ReviewAnalysis --dry-run` lists `:m3NormalizeAnalyzerXml SKIPPED` before `:m3ReviewAnalysis SKIPPED`.
- `./gradlew -p m3/quality-bridge test` fails at master before any test runs, because Gradle 9 needs the JUnit Platform launcher on the test runtime. This is fixed in hsoliwal/gradle#27. With that line added, it gives `BUILD SUCCESSFUL`: 49 tests in 11 classes, 0 failures, 1 skipped (the case that needs `M3_ADAPTER_JAR`).

### Not losses

| Finding | Why it is not a loss |
|---|---|
| `m3/quality-bridge/src/main/java/io/synexia/gradle/quality/M3AnalysisV1.java`, `TOOK_INCOMING` in merge `2a484742d17a`, `LOST_NAMES` `"ERROR"` | `if ("ERROR".equals(String.valueOf(right.severity())))` became `if (right.severity() == Severity.ERROR)` (master line 158; packed-severity refactor `2f1a4e12019a`, `36a11421f565`). With the fix from #27, `M3AnalysisV1Test` passes 8 of 8. |
| `preserve/fk-gradle-repo-master-20261029`, `preserve/fk-gradle-repo-master-20261030`, `m3/rescue-m3-scratch-fk-gradle-repo-20261114`, all at `cf5e147e95e9` | A sweep commit with an **empty tree**: 29,527 deletions on top of `419a77cf61af`, which is already in master. **Never merge these refs**, because a merge would delete the whole tree. Retiring them needs the owner's confirmation and has not been done. |
| `m3/superset-staging-20261102` (`ddc16b5ee833`) | Its tree `e8d52b463c6a` equals that of master merge #7 `032d0fbf44f7`, which has the same parents |

### Upstream files the fork edits (conflict hotspots for every upstream sync)

| File | Fork change |
|---|---|
| `platforms/jvm/java-compiler-plugin/.../IncrementalCompileTask.java` | `delegate.call()` becomes `execution.call()`; `execution` defaults to `delegate` (+18 −1) |
| `platforms/jvm/java-compiler-worker/.../DiagnosticToProblemListener.java` | A three-argument `buildProblem`; the two-argument overload is kept (+12 −2) |
| `platforms/jvm/jvm-compiler-worker/.../CompileOptions.java` | `@SuppressWarnings({"unused", "this-escape"})` (+21 −2) |
| `build-logic-commons/code-quality-rules/.../checkstyle/required-header.txt` | Adds an owner to the copyright pattern; both upstream owners are kept (+2 −2) |

## Upstream capability losses worth keeping reachable

The upstream upgrading guides (5.0 to 9.x, plus the Gradle 10 deprecations) and 1,408 removal commits from 2008 to 2026 were classified into 36 rows. These are the ones worth an opt-in route. Most can be reached through the Maven fork's polyglot engine, which this fork already calls through `m3PolyglotPlan` and `m3PolyglotRun`:

| Capability | Removed | Commits | Route |
|---|---|---|---|
| Ant integration (`AntBuilder`, `ant.importBuild`, `Project.getAnt`) | **Planned for 10.0** (deprecated in 9.9) | `186db4f3e` | Send `build.xml` through `m3PolyglotRun` to the Maven polyglot Ant targets before 10.0 |
| Software-model native plugins (C, C++, Objective-C/C++, assembler, Windows resources, CUnit, GoogleTest) | **Planned for 10.0** (deprecated in 9.7) | `46889bdc1`, `6541047bb`, `9f22c49b1` | Build the languages that lose coverage through the Make, CMake and Meson polyglot rows |
| Compare Gradle Builds (build-output comparison) | 6.0 | `8d7fa2f86` (#10198), `ec65cec93` | A bridge task for the Maven code-clustering compare gate (`CompareMojo`, JOIN/COVERAGE): an open, local comparison |
| JavaScript plugins (coffeescript, envjs, jshint, rhino) | 7.0 | `09d3ea00e` | Not the Rhino-era plugins: use the npm/pnpm/yarn/bun lifecycle through `m3PolyglotRun` |
| IDE file-generation tasks (`idea*`, `eclipse*`, `eclipseWtp*`) | **Planned for 10.0** (deprecated in 9.6) | upgrading guide only | Medium: `m3EclipseModel` reads the model but writes no files |
| eclipse-cdt plugin | 3.0 (dated) | `47fbdb53f` | Low: the Maven fork already reads CDT models (code-clustering-lsp `cdt` profile, `EclipseModelMojo`) |
| JDepend plugin | 6.0 | `8c072d4a7` | Low: Maven code-clustering atoms can provide package-dependency metrics |
| `-b`/`--build-file`, `-c`/`--settings-file` | 9.0 | upgrading guide only | Low: an upstream design decision |

Everything else was replaced upstream and is not worth restoring:

- Sonar, Jetty, FindBugs (the fork already imports SpotBugs XML, PR #18), OSGi, announce and Play;
- Maven 2 publishing, the legacy `maven` plugin and `uploadArchives`;
- the `compile`/`runtime` configurations and the JVM software model;
- the legacy `ArtifactTransform` API, `IncrementalTaskInputs` and `JacocoMerge`;
- `jcenter()`, the Conventions API, `Project#exec`, and various 9.x cleanups.

## Borrowing

### What this fork can take from the Maven fork (through the pinned-JAR bridge)

Upstream Gradle has 0 paths for each of these (`git ls-tree -r c2c0eb5a`, 29,540 paths):

- **Polyglot lifecycle.** npm/pnpm/yarn/bun, Cargo, Go, Make, CMake, Meson, Python, .NET and Bazel targets in `m3-build.tsv`. This fork already proxies them (`M3PolyglotCommands`, PR #24).
- **Local build cache for those tasks** (hsoliwal/maven#149). `PolyglotCli` gains `--build-cache <folder>`. Gradle's `m3PolyglotRun` can pass it on, so native targets reuse outputs across checkouts. **NOT RUN:** that bridge option is not added here yet.
- **Code clustering** with EXACT/LOGIC/STRUCTURE/NEAR hash planes, JOIN, COVERAGE and the compare gate.
- **`tools/code-recipes`** OpenRewrite recipes. They do not depend on the build tool, and the `@rewrite` provider already runs them.
- **Mavenizer.** It turns 13 descriptor kinds into `pom.xml` plus `m3-build.tsv`. Upstream `gradle init` converts only `pom.xml`.
- **Static-analysis normalisation** (Checkstyle, PMD, CPD and SpotBugs XML, SARIF, Semgrep JSON). It is already shared through `m3NormalizeAnalyzerXml`, `m3ReviewAnalysis` and the baseline delta (PRs #9–#23).

### What the Maven fork can take from Gradle

| Gradle capability | Maven fork status | Value |
|---|---|---|
| Local build cache (`build-cache-local`) | Done for polyglot tasks in hsoliwal/maven#149 (design adapted, no code copied) | High |
| Dependency locking (`LockFileReaderWriter`) | Missing | High (next) |
| Dependency verification metadata (`artifacts/verification`) | Partial (checksums only) | High |
| Up-to-date checks, fingerprint normalisers | Partial (`InputStamps`) | High |
| Problems API (147 paths) | Diagnostics have no schema | Schema for smart-builder `diagnose` |
| Tooling API progress events (210 paths), `BuildOperationTrace` | No JSONL writer at master | Event taxonomy for JSONL build events |
| `Maven2Gradle` (build-init) | Mavenizer exists | Test oracle for the reverse direction |
| Toolchain discovery, OS/arch variants, `swiftpm-export` | Partial | Native build variants |
| Remote cache, configuration cache, file watching, daemon, composite builds, workers | Partial or missing | Medium |

## NOT RUN

- Retiring the three empty-tree sweep refs (needs the owner's confirmation).
- The `--build-cache` option in `m3PolyglotRun`.
- A build of the whole Gradle tree. Only `m3/quality-bridge` and a consumer build were run.
- CI for the PRs: the fork's last Actions runs (2026-10-22) were cancelled, and `m3-gradle-quality-bridge.yml` has no runs.
