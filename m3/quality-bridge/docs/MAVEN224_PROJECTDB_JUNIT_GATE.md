<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven #224: six actual Gradle project-DB TestKit receipts

The source owner is **merged Maven #224** at
`77f3b7fbbf847c28d591973bb153b00b5237712e`, following its merged
M3JDK DB fixture-discovery repair **#223** at
`13b5c0162161cd8aaa3d8a05dea5e916bc16af31`.
Gradle does not implement a second JUnit XML parser or change its project
database, native cache admission or task graph.

The standalone Gradle acceptance workflow
`.github/workflows/m3-gradle-maven224-projectdb-junit.yml` runs on
Java **17 and 21**. It builds the **original Maven** polyglot and project-DB
JARs from that exact source revision, then runs current Gradle TestKit with
both original artifact locations supplied explicitly. It SHA-256 checks the
original Maven-owned
`tools/project-db/scripts/verify-gradle-testkit-results.sh` before
executing it against Gradle's actual generated JUnit XML.

## Three suites; exactly six required cases

| Suite | Required cases |
| --- | --- |
| `M3ProjectDbRealEngineTest` | 1 — actual Maven ingest/query/provenance |
| `M3ProjectDbGradleNativeRealEngineTest` | 1 — native Gradle JUnit provenance without a POM |
| `M3ProjectDbTaskTest` | 4 — explicit task behavior and fail-closed refusals |

All three suite names and six testcase IDs are checked by **Maven's own
#224 script**. Absent, skipped, failed, errored or unexpectedly duplicated
project-DB test-suite receipts fail qualification.

The current Gradle fork also has
`M3ProjectDbPrunedSymlinkRealEngineTest` (from Maven #208 parity), so a
wildcard selector such as `*M3ProjectDb*` would accidentally run a fourth
suite and invalidate the source owner's six-case contract. This workflow
selects the original three classes **by full name**. The separate symlink
qualification remains in its own source-pinned lane.

## Recipe, proofs and limits

- Reuse the existing canonical opt-in OpenRewrite consumer recipe
  [`EnableM3QualityBridge`](../recipes/enable-m3-quality-bridge.yml);
  the workflow changes no user build scripts or public APIs.
- Local static guard:
  `sh m3/quality-bridge/scripts/verify-maven224-projectdb-junit-workflow.sh`.
- Maven #224 documented original historical JDK17/21 artifacts with **6/6
  non-skipped cases each**, and independently refused five corrupted
  receipts. Those are historical observations, not results for this new
  Gradle workflow.
- Hosted Maven `verify`, new Gradle TestKit JDK17/21 execution, actual
  M3JDK21 native backing, cross-OS qualification and full OpenRewrite SDK
  replay remain separate gates; do not claim cache-hit correctness or
  successful native-driver qualification from this JUnit evidence alone.
