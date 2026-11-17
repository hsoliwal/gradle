<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native Gradle → Maven IDE delta exchange (M3-IDE-DELTA-V1)

Source owner: hsoliwal/gradle, m3/quality-bridge.
Wire owner: hsoliwal/maven, merged PRs #78 and #82,
api/maven-api-core/.../M3ExternalBuildWire.java.

This is an **opt-in, non-authoritative adapter**. Gradle provides its own
InputChanges; the Maven wire format carries only source-owner change hints.
It does **not** authorize Maven goals, Gradle task execution, compilation,
tests, artifacts, dependency resolution or build-cache reuse to be skipped.

## Producer

Apply the standalone io.synexia.m3.quality plugin from this fork. Its
m3IdeDeltaExport task is registered lazily, independent of normal build,
check, test, compileJava, OpenRewrite and language-tool tasks.

~~~groovy
plugins { id 'io.synexia.m3.quality' }

tasks.named('m3IdeDeltaExport') {
    // Default sourceFiles is project.fileTree('src').
    sourceFiles.from(fileTree('generated-sources'))
    // Scoped to the receiving workspace; defaults to rootProject.name
    // plus ':' plus project.path.
    projectIdentifier.set('build-root:project-id')
}
~~~

Then explicitly invoke ./gradlew m3IdeDeltaExport. The task writes:

- build/reports/m3/ide-delta.tsv: canonical UTF-8, LF, 8 MiB bounded,
  deterministic M3-IDE-DELTA-V1 rows
- build/reports/m3/ide-delta.sha256: SHA-256 of exact packet bytes,
  lowercase hexadecimal plus LF

For a cold invocation, missing Gradle history or otherwise unavailable
incremental state, the packet reports kind=FULL, no change rows and
complete=false. For incremental executions, Gradle's InputChanges events
become project-relative ADDED, MODIFIED or DELETED rows, sorted by path.
Gradle ChangeType.REMOVED maps to Maven's DELETED. All sources must lie
within the project and outside the build-report root. The output root
must be inside the project. Symlinks in those paths, foreign input files
and invalid or duplicate rows fail closed.

complete=false is **intentional even for incremental changes**. The
producer cannot infer the effective build model, language compiler closure,
dependencies, generated sources or classpath just from one incremental
file collection. Maven requires separately supplied source-owner generation
facts and independently verified model, toolchain, dependency and source-root
digests before classifying even an incremental host hint.

A packet from a previous task execution is not a fresh build event:
Gradle may mark this producer task UP-TO-DATE when no source has changed.
The receiver must establish its own current source/generation identity.
Never promote a stored packet into a successful build receipt.

## Wire compatibility

Maven accepts versioned fields in this order:

~~~text
M3-IDE-DELTA-V1
provider	gradle
project64	YnVpbGQtcm9vdDpwcm9qZWN0LWlk
kind	INCREMENTAL
complete	false
change	MODIFIED	src/main/java/A.java
~~~

project64 is canonical **unpadded Base64URL** of UTF-8 project identity.
The receiver must scope that identity to the actual workspace; a project
name alone does not uniquely identify a checkout. No source bytes, absolute
machine paths or model secrets are placed in the packet.

The dependency-free M3IdeDeltaWire mirrors Maven's v1 format: UTF-8 without
BOM, final LF, 8 MiB, at most 65,536 changes, sorted unique project-relative
paths, provider up to 128 characters, project identifier up to 256 characters,
and relative paths up to 2,048 characters. A SHA-256 digest establishes
**byte identity**, not source completeness, toolchain equivalence or
build-cache eligibility.

## Qualification

~~~sh
sh m3/quality-bridge/scripts/verify-ide-delta.sh
./gradlew -p m3/quality-bridge test --tests '*M3IdeDelta*'
./gradlew -p m3/quality-bridge tasks --all --configuration-cache
~~~

The standalone Java 17 test reproduces Maven wire example bytes and
rejects invalid encodings, duplicate/unsorted rows, traversal, malformed
UTF-8 and completeness claims. Gradle TestKit exercises real InputChanges:
cold, modified and removed sources, digest consistency, lazy registration
and foreign source refusal. These tests must execute in CI for acceptance;
committed tests alone are not evidence of a passing build.

Gradle build-cache replay is disabled for the delta task, while its normal
local incremental execution history remains available. The task does not
invoke Maven or download language runtimes, tools or plugins.
