<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 Gradle/Maven combined CI restoration — 2026-11-18

Target: GitHub branch `fix/m3-quality-bridge-real-cache-ci-20261118`,
reviewed against the current Gradle `master` after merged PRs
#28, #30, #32, #33 and #34.

## Observed history loss

The merged combined workflow retained the Maven #149 **argument-vector**
check but had lost the separately authored real-engine
`M3PolyglotCacheRealEngineTest` lane. This meant that the workflow would
not have tested cross-checkout cache restoration or corrupted blob refusal,
even if all other jobs passed.

The change restores the **actual merged, source-pinned Maven #149 JAR**
TestKit check alongside the pre-existing tests for module state,
language fingerprints, static analysis normalization, portable
backend selectors and native IDE delta exchange.

It also adds a `push` trigger for `master` scoped to the quality
bridge, workflow and convergence register paths. The earlier workflow
had only a `pull_request` and manual trigger. A repository with
Actions disabled still needs its authorized owner to enable Actions;
this file does not change repository security settings.

## Locally executed, exact-source evidence

All paths below were reconstructed and checked against their current
GitHub **Git blob SHA-1**, then compiled on OpenJDK 21.0.12.1
using `javac --release 17 -encoding UTF-8 -Xlint:all -Werror`.
Commands did **not** require Maven or Gradle to be installed.

| Gate | Input blob identities | Executed result |
| --- | --- | --- |
| Gradle / Maven IDE wire | `M3IdeDeltaWire.java` `7f3c42fbee79a4383b4fb5eec2019ef9216c3283` + `M3IdeDeltaWireContractMain.java` `e4a9885ec9ead288f8509c7b781bbf073699eda5` | PASS, canonical Maven bytes and malformed input refusals |
| Explicit Maven #149 cache argv | `M3QualityCommands.java` `be54216c2e51d269b4dae24f828e8bb06ebc1df9`, `M3PolyglotCommands.java` `78026fb0afa1acbece9d6a137b44a6d287b014ea`, `M3PolyglotCacheArgsContractMain.java` `ad36ab7dd78d3758343d8de65bdb726d555289fe` | PASS, backward-compatible overloads and run-only cache arguments |
| Repaired CI workflow | `m3-gradle-quality-bridge.yml` `d468d2d75a3009f2bda01821a2ed421271851d97`, `verify-quality-workflow.sh` `9931e32db0926f055a25c9928bbec7bf09b85b45` | PASS, shell script syntax and execution, canonical YAML parsing, 18 workflow steps and all 13 source-pin/lane assertions |

## Negative guard challenge (executed)

The exact workflow source was copied to an isolated review directory,
then the `M3PolyglotCacheRealEngineTest` invocation was deliberately
replaced with an unrelated test selector. Running
`sh m3/quality-bridge/scripts/verify-quality-workflow.sh` rejected it
with the exact expected diagnostic:

~~~text
Expected one workflow contract entry (test --tests '*M3PolyglotCacheRealEngineTest' --rerun-tasks), found 0
~~~

After restoring the original Git-blob-matched YAML, the same guard
returned `PASS: source-pinned M3 Gradle/Maven CI lanes retained`.
This qualifies the guard's positive and missing-lane refusal behavior,
not the underlying TestKit runtime.

## Unrun mandatory gates

- Gradle 9.9.1 distribution, `m3/quality-bridge test` and Gradle
  TestKit real compiler/cache/IDE event integration: NOT RUN locally.
- Real Maven #149 source-pinned cache and independently installed
  MIndexDB/JDBC providers: NOT RUN locally.
- JDK 17 runtime, Windows/macOS full integration: NOT RUN locally.
- GitHub Actions: no new run reported on Gradle commits as of this
  review. The repository's most recent available workflow runs date
  from October 22, 2026 and were canceled. No green CI claim.
- Sealed OpenRewrite recipe replay, compile/test/runtime and full
  semantic equivalence qualification: NOT RUN.

Never infer a successful Gradle or Maven build from the standalone
Java tests or the existence of an Actions YAML file. The GitHub PR
should remain a draft until external CI and cross-platform runtime
evidence is available.
