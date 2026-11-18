<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 project DB Gradle bridge — qualification receipt (2026-11-18)

Upstream contract: hsoliwal/maven#184 and #190,
source pin: merged #190 2e15f2a4f0c92f6593167f573b0222e214a74b0f.
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

## Blocked gates

- Full Gradle TestKit, configuration-cache reuse and Gradle API
  compilation: NOT RUN locally; no Gradle distribution is installed.
- Real Maven project-db JAR plus original M3IndexDB/IndexStore:
  NOT RUN locally; Maven is not installed and GitHub/Maven Central
  DNS is unavailable to this container.
- Real-engine cross-fork JUnit, JDK 17/21 runtime, actual POM,
  Surefire/Failsafe receipts and Windows/macOS: CI PENDING.
- M3JDK21 platform module provider, native Gradle non-JUnit build-data ingestion
  and Maven #186/#187 storage optimizations: NOT CLAIMED.
- OpenRewrite source-sealed recipe replay, DIFF/LINT/COMPILE/TEST
  compatibility gates and full Gradle distribution tests: PENDING.

No Maven/Gradle task cache-hit or goal-skip authority is granted by
the new bridge. Keep PR draft until CI shows actual source-owned
engine and task integration results.
