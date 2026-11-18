<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 project DB Gradle bridge — qualification receipt (2026-11-18)

Upstream contract: hsoliwal/maven#184 and #190,
original #190 merge: 2e15f2a4f0c92f6593167f573b0222e214a74b0f;
reviewed CI source pin: merged #194 64e5ce257c46207eac32407f00979cfc4a79767e.
Receiving branch: hsoliwal/gradle
feat/m3-gradle-project-db-cli-20261118.

## Executed tests (source-identical)

Test environment: OpenJDK 21.0.12.1 with javac --release 17 and
-Xlint:all -Werror. The exact files were reconstructed from the
receiving branch and checked with git hash-object against GitHub's
content blob hashes.

| Component | Git blob SHA-1 |
| --- | --- |
| M3QualityCommands.java (existing) | be54216c2e51d269b4dae24f828e8bb06ebc1df9 |
| M3PolyglotCommands.java (existing) | 78026fb0afa1acbece9d6a137b44a6d287b014ea |
| M3ProjectDbCommands.java | 775c8b7ade2d0e787c78209fb7c940f228beeecc |
| M3ProjectDbCommandsContractMain.java | 380986d1ed8535c3fc747cfa757b19e80fbaf0b7 |

~~~sh
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d classes \
  M3QualityCommands.java M3PolyglotCommands.java \
  M3ProjectDbCommands.java M3ProjectDbCommandsContractMain.java
java -cp classes io.synexia.gradle.quality.M3ProjectDbCommandsContractMain
~~~

**PASS**:
~~~text
PASS: pinned Maven project DB argv, ingest/query isolation and refusals
~~~

This validates no-shell, bounded, immutable argument vectors, distinct
ingest/query flags, Maven runtime classpath order, JSON retrieval options,
invalid modes/questions and out-of-range limits. It does not prove the
Gradle task class compiles against the installed Gradle API.

## Workflow preservation gate

The branch's workflow and guard sources were reconstructed from GitHub
and verified by git hash-object:

- .github/workflows/m3-gradle-quality-bridge.yml:
  fddd8014753b52156926307ac479f12edf53312b
- m3/quality-bridge/scripts/verify-quality-workflow.sh:
  bf5dc8c5cdeec1823dfe935c921f27c1415bbe92

Shell guard: **PASS**. YAML parsed into two jobs:
standalone-gradle-bridge (19 steps) and m3-project-db-crossfork
(6 steps), including JDK 17/21 matrices. Maven project-DB source is
pinned to merge #190 and now includes a second native
Gradle-only JUnit XML TestKit case (without POM), requiring genuine
source-line provenance and warm-ingest reuse. The original Maven
#184 project DB JAR does not have this native Gradle scanner. The negative test replaced the
M3ProjectDbRealEngineTest step with an unrelated selector: the guard
refused it with 'found 0'; restoring the original workflow produced
PASS again.

## Original Maven #190 Gradle-source parser — executed

The exact seven source files from the **merged** Maven #190 source revision
were materialized locally. Git blob identity was checked against the
original GitHub source readbacks, not inferred from filenames:

| JDK-only original source | Git blob SHA-1 |
| --- | --- |
| Modules.java | 0d02389f19c214aa23b03e764ce68de45f61ce4f |
| Sources.java | 756ba5bb21190afa32d6e2a77f161b03783ca193 |
| Source.java | 64f4d9860cc330cd957529ff7c611defae6f70e8 |
| Chunk.java | a35fdd29d302b5b94a9008b4adb4fb7606bc8fa6 |
| Text.java | cf324b80d0c116edfe272f48af70a7815f6efddd |
| SurefireParser.java | e3fb07ec9ac3772ff9b563a00bb032c45ff6f631 |
| GradleSourceDiscoveryContractMain.java | 60e56b0c5314c40acf7176d85edb74b34d46d11f |

Executed using OpenJDK 21 with Java 17 API/bytecode targeting:

~~~sh
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d classes \
    Source.java Sources.java Modules.java Chunk.java Text.java \
    SurefireParser.java GradleSourceDiscoveryContractMain.java
java -cp classes org.apache.maven.plugins.projectdb.GradleSourceDiscoveryContractMain
~~~

**PASS**: native Gradle module discovery without a POM; original Gradle
JUnit failure/passing-case parsing; source-file/line provenance;
no accidental discovery of build outputs as project modules.

This is an executed **source-identical JDK-only** gate, not a Maven
plugin descriptor, full ProjectDb storage, Gradle TestKit or cross-OS
qualification result. Maven's native database and full incremental
catalog/derived-query cases remain separate integration gates.

## Real CI failure and source-pinned owner repair

The Maven #190 native Gradle-evidence matrix **executed** on both JDK 17
and JDK 21. In each job, the standalone original Gradle-only JUnit
source/parser test passed; the Maven polyglot module-state contract then
failed while planning, before the project-DB integration tests could run:

~~~text
NullPointerException: ModuleStateDb.commitStaged() because "store" is null
TaskRunner.runTasks(TaskRunner.java:293)
ModuleStateContractTestMain.doesNotWriteDuringPlanning(...)
~~~

The cause was a dry-run path with incremental=true: read-only planning
never opens ModuleStateDb but the end-of-graph code tried to commit it.
Maven PR #194 corrected the commit gate to require an actual executable
incremental run; its regression now exercises embedded/file/SQLite/H2/
native/ext-* planning with an absent cache directory.

Gradle's original cross-fork CI source pin still referenced Maven #190,
which contained this known build failure. This follow-up changes only the
pinned checkout to **merged Maven #194** and updates the workflow guard.
The existing source-identical JDK-only Maven #190 parser proof remains
valid, but a merged source pin, passed parser, or historical CI failure
does **not** establish a passing full Maven polyglot/project-DB build.

## Re-executed against merged Maven #194

The JDK-only native Gradle evidence test was re-run after the #194
Maven source revision was pinned. Exact GitHub blob hashes for
Modules, Sources, Source, Chunk, Text, SurefireParser and
GradleSourceDiscoveryContractMain were rechecked. All seven remain
identical to the source-identical hashes recorded earlier in this
receipt. On OpenJDK 21:

~~~sh
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d classes \
  Modules.java Sources.java Source.java Chunk.java Text.java \
  SurefireParser.java GradleSourceDiscoveryContractMain.java
java -cp classes org.apache.maven.plugins.projectdb.GradleSourceDiscoveryContractMain
~~~

**PASS**: native Gradle module and JUnit case evidence via the original
Maven parsers. The existing TaskRunner #194 transactional fix has not
been exercised by this JDK-only compilation; that requires the full
Maven polyglot module-state contract and its CI.

## Successful original Maven JDK 17/21 CI

The original Maven project-DB CI rerun on Maven #196 source
`e1310509b624bc63240811a8dce01478d268a812`, including
the #194 TaskRunner read-only planning fix, completed successfully:

- [GitHub Actions run 44233563771](https://github.com/hsoliwal/maven/actions/runs/44233563771)
- JDK 17 job `134564176348`: **SUCCESS**.
- JDK 21 job `134564176690`: **SUCCESS**.

Both logs show `BUILD SUCCESS` for the original polyglot storage
owner and again for `tools/project-db/pom.xml verify`, including:

| Executed original test | JDK 17 | JDK 21 |
| --- | --- | --- |
| Native Gradle-only module/JUnit source parser | PASS | PASS |
| Persistent module-state contract | 71 checks PASS | 71 checks PASS |
| Backend integration | 99 checks PASS | 99 checks PASS |
| Portable growing-array receipt oracle | 117,044 PASS | 117,044 PASS |
| Project database and retrieval | 62 checks PASS | 62 checks PASS |
| Native Gradle JUnit evidence | 10 checks PASS | 10 checks PASS |

This is **executed Maven source-owner CI**. It corrects the earlier
historical #190 failing job receipt, but it is **not** a Gradle TestKit
acceptance or a qualification of later Maven commits.

Maven #202 subsequently added a second source-pinned **real Gradle
TestKit consumer** job to the active Maven Actions workflow, using
reviewed Gradle commit `e8dbf558fcc8a83c580914fbcc4cb10fe4281eb6`
and the original Maven JARs built from Maven source. Maven #207 fixed
the workflow guard's two-matrix count. Those new Gradle consumer jobs
were **queued**, not yet proven green when this receipt was updated.
The newer Maven #205/#208 early-pruned Gradle module traversal and
symlink compatibility are independent follow-ups: exact-source Java17
oracle tests passed, but they are not covered by run 44233563771.

## Blocked gates

- Full Gradle TestKit, configuration-cache reuse and Gradle API
  compilation: NOT RUN locally; no Gradle distribution is installed.
- Original Maven project-DB and M3IndexDB/IndexStore CI:
  JDK 17/21 PASS as recorded above. NOT RUN in this local container;
  Maven is unavailable here.
- Gradle TestKit with the source-built Maven JARs, Gradle's original
  plugin/task interface, Windows/macOS, and independent build/cache
  semantic qualification: CI PENDING.
- M3JDK21 platform module provider, native Gradle non-JUnit build-data ingestion
  and Maven #186/#187 storage optimizations: NOT CLAIMED.
- OpenRewrite source-sealed recipe replay, DIFF/LINT/COMPILE/TEST
  compatibility gates and full Gradle distribution tests: PENDING.

No Maven/Gradle task cache-hit or goal-skip authority is granted by
the new bridge. Keep new CI source-pin changes unqualified until actual source-owned engine and Gradle task integration checks pass.
