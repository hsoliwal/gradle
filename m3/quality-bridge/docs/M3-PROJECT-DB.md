<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 Gradle → Maven project database and CPU-only retrieval

Source owner: Maven [PR #184](https://github.com/hsoliwal/maven/pull/184),
extended by merged [PR #190](https://github.com/hsoliwal/maven/pull/190),
CI source pin 64e5ce257c46207eac32407f00979cfc4a79767e,
which also includes Maven #194's read-only planning fix.
The original ProjectDbCli and M3IndexDB/IndexStore code remain Maven/JDK
owned. This Gradle component never copies storage, retrieval, parser or MCP
implementations. This is not Gradle's local/remote build cache and cannot
authorize a Gradle task skip or cache hit.

## Explicit configuration

Build and review Maven's original m3-polyglot-maven-plugin and
maven-project-db-plugin JARs from the pinned source. Compute a separate
SHA-256 for each actual JAR. With io.synexia.m3.quality applied:

~~~groovy
['m3ProjectDbIngest', 'm3ProjectDbQuery'].each { name ->
    tasks.named(name) {
        projectDbJar.set(layout.projectDirectory.file('vendor/maven-project-db-plugin.jar'))
        projectDbSha256.set(providers.gradleProperty('m3.projectdb.jar.sha256'))
        polyglotJar.set(layout.projectDirectory.file('vendor/m3-polyglot-maven-plugin.jar'))
        polyglotSha256.set(providers.gradleProperty('m3.polyglot.jar.sha256'))
    }
}
tasks.named('m3ProjectDbIngest') {
    // Optional; needs a regular file inside the project.
    buildLog.set(layout.projectDirectory.file('build.log'))
}
tasks.named('m3ProjectDbQuery') {
    question.set('why did compilation fail?')
    topK.set(5)
}
~~~

The tasks are deliberately **not** attached to build, check, compileJava or
Maven lifecycle goals. Invoke m3ProjectDbIngest first and
m3ProjectDbQuery separately. The query is not an LLM or inferred action; it
uses Maven's original CPU-only BM25, hashing-vector and exact-facet retrieval.
No network access, downloads or AI calls are introduced by Gradle.

Default storage: build/m3/project-db within the Gradle project. The
original Maven ProjectDbCli receives --store pointing to this directory;
it owns its M3IndexDB artifacts, catalog, incremental ingest and .gitignore
publication. A custom storageDirectory must be under the project
build directory, target/project-db, .mvn/project-db or .m3/project-db.
An arbitrary source-directory store is refused to prevent accidental
.gitignore or database writes alongside source files.
Each invocation is an untracked Gradle task because the real source corpus
and Maven-managed state cannot be proven complete by Gradle's task inputs.

A query **requires prior ingestion** into the selected store: the Gradle
adapter checks for the existing Maven store directory and its protective
.gitignore before launching the Maven CLI. A missing store fails before
any external process starts rather than letting a question initialize an
empty database. This is an admission check, not a new database format or
an assertion that every record in an existing store is valid; the original
Maven IndexStore remains responsible for reading and verifying artifacts.

Results go to build/reports/m3/project-db/ingest.json and query.json,
respectively. The Gradle bridge captures at most 1 MiB of source-pinned CLI
stdout; it requires valid UTF-8 and a single JSON object, then atomically
publishes the report. Original Maven JSON retains its build id, and query
answers, when present, retain file/line/build provenance. A SHA-256 pin
establishes identity of JAR bytes, not semantic correctness of the source.

## What is actually indexed

This bridge retains the original Maven project evidence supported by
PR #184: polyglot receipts, target/surefire-reports and
target/failsafe-reports, effective POMs, dependency-tree text,
clustering TSV, Maven build events and an explicit build log.
Merged Maven #190 additionally recognizes Gradle project/module
descriptors without any POM and ingests native
build/test-results/<testTask>/TEST-*.xml from the existing
SurefireParser/IndexStore pipeline. The test's original source
frame, report path, and build identifier remain retrievable.

This **does not ingest** Gradle's native dependency resolution,
configuration cache, compiler ABI, task graph, arbitrary build outputs
or build-cache generation. A Gradle JUnit report establishes its
declared test outcome only: it does not authorize skipping Gradle
tasks or classify an entire build as successful.

The Maven original project database uses one storage interface (M3IndexDB
through IndexStore), sharing the source-owned backend contract with
polyglot state. It does **not** use Gradle's native task cache and does
not establish end-to-end deduplication of all build facts in the system.

## Testing and limitations

~~~sh
sh m3/quality-bridge/scripts/verify-project-db-args.sh
./gradlew -p m3/quality-bridge test --tests '*M3ProjectDb*'
./gradlew -p m3/quality-bridge tasks --all --configuration-cache
~~~

The standalone Java 17 test checks the original argument vector, classpath
construction, query/ingest isolation, bounded questions, no-shell invocation
and refusals. Gradle TestKit checks task registration, SHA-256 pinning,
source/store boundaries and JSON reports, including a fake CLI that does
not claim storage integration. The cross-fork CI job checks out the exact merged Maven #194 commit
(which includes #184, #190 and the read-only M3DB planning correction),
builds its Java 17 project-DB/IndexStore owner, runs the Maven project-DB contracts,
and invokes the original JAR from Gradle on JDK 17 and 21.
A separate real-engine TestKit fixture contains **no POM** and
requires native Gradle XML failures to retain source-line provenance,
with a second ingest reusing unchanged test-report bytes.

The newer merged Maven #186 array-backed batched receipts and #187
engine-transcript safety fix are **not** silently claimed qualified by this
bridge. Pin changes require their own source review and runtime tests.
Until real CI results are available this is an additive, opt-in review
candidate, not an assertion of production readiness.
