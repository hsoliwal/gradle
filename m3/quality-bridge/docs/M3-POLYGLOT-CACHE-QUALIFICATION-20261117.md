<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven polyglot local-cache bridge qualification — 2026-11-17

Source: Gradle PR #34 receiving Maven #149 (merged
`b47e9a1e9173502c19de9bb6657a4f88b3267487`, including #155).

## Executed standalone Java 17 gate

Exact repository postimages were reconstructed locally and matched their
Git blobs before compiling:

| Source | Git blob SHA-1 |
| --- | --- |
| `M3QualityCommands.java` | `be54216c2e51d269b4dae24f828e8bb06ebc1df9` |
| `M3PolyglotCommands.java` | `78026fb0afa1acbece9d6a137b44a6d287b014ea` |
| `M3PolyglotCacheArgsContractMain.java` | `ad36ab7dd78d3758343d8de65bdb726d555289fe` |

On JDK 21.0.12.1, ran:

~~~sh
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d classes \
  M3QualityCommands.java M3PolyglotCommands.java M3PolyglotCacheArgsContractMain.java
java -cp classes io.synexia.gradle.quality.M3PolyglotCacheArgsContractMain
~~~

**Result: PASS**. Output:

~~~text
PASS: Gradle adapter forwards explicit Maven #149 local cache with old signatures intact
~~~

Verified original Java method behavior, explicit literal `--build-cache`
arguments, cache with `--incremental --state-backend file`, cache without
incremental mode, and strict refusal for a plan or invalid backend pairing.

## Unexecuted mandatory qualification

Gradle 9.9.1/TestKit could not run locally, because no Gradle distribution
is installed and external distribution DNS is unavailable. In particular,
these are **not yet proven** by this result:

- Full Gradle task compilation and Gradle TestKit cache-directory validation.
- Cross-checkout original Maven #149 engine execution under Gradle.
- Cold store, separate checkout reuse without launching a native compiler,
  changed input miss, and corrupt blob refusal.
- JDK 17 runtime, Windows/macOS filesystem semantics and parallel writers.
- Full Gradle configuration-cache acceptance and native Gradle build matrix.
- Recipe-first Synexia OpenRewrite replay and fixed-point proof.

The CI workflow pins the **actual merged Maven** #149 source and executes
the real-engine integration TestKit test in the JDK17/JDK21 matrix.
Source hashes and this standalone test are not substitutes for its result.

## Caching and contract restrictions

Maven #149's cache key does not certify toolchain versions, effective
environment, dynamic inputs, ABI or semantic behavior. The opt-in cache
is suitable only when those dependencies are explicitly declared and
independently qualified. The Gradle adapter does not override default
build/cache behavior and never treats a Maven FROM-CACHE log as a native
Gradle task-cache receipt.

**Status: DRAFT/HOLD** pending the integration acceptance matrix.
