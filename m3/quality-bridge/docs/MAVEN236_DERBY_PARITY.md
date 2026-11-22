<!-- SPDX-License-Identifier: Apache-2.0 -->
# Original Maven #235/#236 Derby → Gradle runtime qualification

## Original source, not another database

- Original owner: [merged Maven #235](https://github.com/hsoliwal/maven/pull/235), Apache Derby 10.16.1.1 compatible with Java 17. The narrow diagnostic routing follow-up [#236](https://github.com/hsoliwal/maven/pull/236) merged at immutable Maven commit `2bc1468e9f318724b01ae4d51b3078aa2a09a6d3`.
- The original `M3DerbyBackendProvider` implements the **existing** `M3ModuleDbBackendProvider` SPI with ID `ext-derby-v1`; it persists the original sealed v2 M3 module-state image, not an alternative cache format. Maven owns the transactions, WAL/dirty-generation recovery, cross-process leases, revocation and read-back checks.
- Gradle already supports `ext-*` through `m3PolyglotRun`. This batch adds **no production storage adapter or extra cache key**. It adds source-pinned real-JDBC acceptance only. `m3PolyglotPlan` remains read-only; `m3PolyglotRun` requires explicit incremental mode and backend selection and remains an untracked opt-in task.
- The original Maven engine JAR and each optional runtime JAR are independently verified by `M3PolyglotCommands.verifyEngine` / `M3ProviderClasspath.verify` before launching Maven. A missing driver refuses before starting the declared language tool; no embedded M3IndexDB fallback is allowed.

## Explicit operator example

```groovy
tasks.named('m3PolyglotRun') {
    engineJar.set(layout.projectDirectory.file('vendor/m3-polyglot-maven-plugin.jar'))
    engineSha256.set(providers.gradleProperty('m3.polyglot.sha256'))
    incremental.set(true)
    stateBackend.set('ext-derby-v1')

    // Operator-installed, independently SHA-256-pinned original Derby 10.16.1.1 JARs.
    // Include all test/runtime transitive Derby closure JARs, each by a unique basename.
    providerJars.from(files(
        'vendor/derby-10.16.1.1.jar',
        'vendor/derbytools-10.16.1.1.jar'
    ))
    providerJarSha256.put('derby-10.16.1.1.jar', providers.gradleProperty('m3.derby.sha256'))
    providerJarSha256.put('derbytools-10.16.1.1.jar', providers.gradleProperty('m3.derbytools.sha256'))
    // If present, derbyshared-10.16.1.1.jar is also required and must be separately pinned.
}
```

The optional source-owned Derby provider also uses any installed Derby transitive runtime dependencies supplied on the explicit Java classpath. **Do not ignore** `derbyshared` or other actual runtime closure JARs. No JAR is fetched by the Gradle plugin.

Apache Derby may otherwise write process-global `derby.log` in the working directory. In the dedicated CI lane, `JAVA_TOOL_OPTIONS=-Dderby.stream.error.field=java.lang.System.err` is set **explicitly by the test operator**, not silently by the production plugin or as a new unrestricted JVM argument selector.

## Executable acceptance and limits

New source-pinned Java 17/21 workflow `.github/workflows/m3-gradle-maven236-derby.yml` checks out exactly the Maven #236 merge commit, runs the original Maven `-Pm3-derby verify` with real Derby JDBC and crash/recovery contracts, stages only test-scoped original `org.apache.derby` JARs, and runs the Gradle consumer against those exact JARs. It refuses missing or skipped cases. The four Gradle TestKit checks cover absent driver/no tool/no fallback, cold→new-JVM warm state verified by content, same-size restored-mtime input invalidation and output tampering, wrong driver SHA rejected before engine execution, and symlinked DB directory refusal.

Reuse the existing opt-in OpenRewrite recipe `io.synexia.gradle.recipe.EnableM3PolyglotStateBridge`. It only applies the separately installed M3 plugin to a chosen Gradle build. It does not enable a backend, add project dependencies, or attach any task to `build` or `check`.

**HOLD for exact-head CI:** Maven and Gradle Java17/21 jobs, real driver closure, four non-skipped TestKit results, Windows/macOS, full Gradle build/cache matrix, independent OpenRewrite SDK replay and authorized human DCO review. A test source file or static guard is not an executed runtime receipt. Unproven cache hits, Maven/Gradle storage substitution, or source-built M3JDK21 acceptance are not claimed.

## History scope

Reviewed main Maven #143/#147/#184/#206/#209/#216/#227/#235/#236 and Gradle #39/#41/#49/#59/#60/#61/#71 lineages, bounded to the relevant database and polyglot edges. The project-wide all-ref/full-history source-loss audit is **not** claimed complete. All existing Maven and Gradle public APIs and Gradle task/cache ownership remain unchanged.
