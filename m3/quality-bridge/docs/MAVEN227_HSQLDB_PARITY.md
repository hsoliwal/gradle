# Maven #227 HSQLDB backend → original Gradle bridge

## Branch and PR provenance

- Original owner: [hsoliwal/maven#227](https://github.com/hsoliwal/maven/pull/227), merged at `738eb1b78725d7f355b9f4fdb6d34dbad0024963`.
- Historical context: Maven #143/#147 introduced original portable module state; Maven #206/#209 added optional external providers. Maven #227 adds HSQLDB 2.7.4 through the **existing** local JDBC module-state owner. No HSQLDB driver or source is vendored in Gradle.
- Consumer: `m3PolyglotRun` in `hsoliwal/gradle`; the existing OpenRewrite recipe `io.synexia.gradle.recipe.EnableM3PolyglotStateBridge` is reused. No new plugin ID, build hook, task scheduler or database file format.

## Boundary

| Component | Authority |
| --- | --- |
| `M3PolyglotCommands` | Admits **only** literal `hsqldb` on explicit incremental `run`, forms original Maven argv; refuses free-form JDBC URLs and flags |
| `M3ProviderClasspath` | Separately verifies exact SHA-256 for the optional HSQLDB driver JAR before starting Maven; engine is pinned independently |
| Original Maven `M3DbModuleStore` + `M3ModuleJdbcStorage` | Local database creation, commit/rollback, lease, sealed receipt image, failure/refusal, HSQLDB sidecar exclusions and own incremental decisions |
| Gradle `m3PolyglotRun` | Remains **untracked** and explicitly invoked, cannot claim Gradle configuration/build cache correctness from Maven receipts |

The original Maven `hsqldb` CLI option requires `--incremental` and `run`.
The classpath must include an explicit HSQLDB 2.7.4 driver (test/consumer only),
with independent filename-bound SHA-256. No external JDBC address, password,
network driver connection or fallback to embedded state is permitted.

## Tests and gates

- Java 17-compatible offline Gradle argv contract: accepts literal `hsqldb` and retains old overloads; rejects planning/nonincremental execution and forged URLs/uppercase aliases.
- Real original Maven TestKit: missing HSQLDB JDBC driver refuses before invoking a Java marker, without falling back to M3IndexDB or making a database.
- With separately pinned HSQLDB 2.7.4 driver: real original Maven engine publishes to `.m3/module-state-hsqldb.properties`, then a fresh Gradle invocation reopens and accepts a *content-verified* `UP-TO-DATE` while preserving call count. Tampering an output or changing a declared input must re-execute. Incorrect driver SHA must fail *in Gradle* before Maven is invoked.
- [JDK17/21 workflow](../../../.github/workflows/m3-gradle-maven227-hsqldb.yml): checks out exact merged Maven #227, builds original Maven plugin, executes original Maven `-Pm3-hsqldb verify` contracts, resolves test-scope HSQLDB 2.7.4 driver, runs the real Gradle TestKit, then refuses XML with skipped/failed/missing/unexpected case names. Reports retained even on failure.
- Local static/ABI gate: `sh m3/quality-bridge/scripts/verify-portable-backends.sh` and `sh m3/quality-bridge/scripts/verify-maven227-hsqldb-workflow.sh`.

## Qualification limits

Local Java argv checks do **not** qualify JDBC execution or standalone Gradle
test dependencies. Only successful source/JDK-identified hosted receipts can
qualify those claims. Original Maven #227 has additional database crash/restart,
transaction, symlink and corruption contracts; this Gradle bridge does not
replicate its backend implementation or assert Windows/macOS equivalence.
