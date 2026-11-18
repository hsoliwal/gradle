<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven #224 six-case gate, secured by Maven #226/#230 Java17 parser

The initial six-case receipt contract came from **merged Maven #224** at
`77f3b7fbbf847c28d591973bb153b00b5237712e`, following its merged
M3JDK DB fixture-discovery repair **#223** at
`13b5c0162161cd8aaa3d8a05dea5e916bc16af31`.
[Maven #226](https://github.com/hsoliwal/maven/pull/226) then replaced
the initial text-matching shell admission with the original dependency-free,
secure XML parser `M3GradleTestKitReceiptMain.java`. [Maven #230](https://github.com/hsoliwal/maven/pull/230)
subsequently fixed the producer's exact three-class selection. This Gradle
consumer pins merged **Maven #230** at
`d3385d6ca33f4ac7bdc2a9f3b2d02504b5976bed` to include both changes.
Gradle copies no JUnit parser, database, native cache admission or task graph.

The standalone Gradle acceptance workflow
`.github/workflows/m3-gradle-maven224-projectdb-junit.yml` runs on
Java **17 and 21**. It builds the **original Maven** polyglot and project-DB
JARs from that exact source revision, then runs current Gradle TestKit with
both original artifact locations supplied explicitly. It SHA-256 checks
**both** original Maven-owned sources before running the verifier:

- `tools/project-db/scripts/verify-gradle-testkit-results.sh` — SHA-256
  `6b1a98534333c5563469c9ee83434b032d7f792d7cc7b67d727d22804db2456f`.
- `tools/project-db/scripts/M3GradleTestKitReceiptMain.java` — SHA-256
  `0f2d667d6cafc6cf5463775177f57e71a71221173933c320b28fbd675945c979`.

The original Maven wrapper compiles that Java class with
`javac --release 17 -Xlint:all -Werror` into an isolated temporary directory,
then parses Gradle's *real* generated JUnit XML with secure XML-processing
features and no external schema/DTD/entity access. It refuses an XML comment
that merely *pretends* to contain successful tests, malformed attributes,
skips, failures, duplicate/misnested testcase names, unexpected fourth
project-DB suites, symlinks and oversized inputs. The older shell text check
could be spoofed by comments and did not reliably handle attribute order.

## Three suites; exactly six required cases

| Suite | Required cases |
| --- | --- |
| `M3ProjectDbRealEngineTest` | 1 — actual Maven ingest/query/provenance |
| `M3ProjectDbGradleNativeRealEngineTest` | 1 — native Gradle JUnit provenance without a POM |
| `M3ProjectDbTaskTest` | 4 — explicit task behavior and fail-closed refusals |

All three suite names and six testcase IDs are checked by **Maven's original
#226 Java17 parser on the #230 source pin**. Absent, skipped, failed, errored or unexpectedly duplicated
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
  non-skipped cases each**. Maven #226's secure parser also executed against
  both historical actual Gradle archives and rejected eight manipulated XML
  fixtures. Those are historical source-owner observations, not results for
  this upgraded Gradle workflow.
- Hosted Maven `verify`, new Gradle TestKit JDK17/21 execution, actual
  M3JDK21 native backing, cross-OS qualification and full OpenRewrite SDK
  replay remain separate gates; do not claim cache-hit correctness or
  successful native-driver qualification from this JUnit evidence alone.
