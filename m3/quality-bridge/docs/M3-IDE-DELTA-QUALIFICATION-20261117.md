<!-- SPDX-License-Identifier: Apache-2.0 -->
# IDE delta Gradle/Maven cross-build qualification — 2026-11-17

Source under test: hsoliwal/gradle PR #33, branch
`feat/m3-gradle-native-ide-delta-20261117`.

## Executed source-identical JDK17 contract

A local JDK 21.0.12.1 environment, with `javac` and Git, compiled
two GitHub readback-verified files. Their Git blob hashes exactly matched:

| Path | Git blob SHA-1 |
| --- | --- |
| `M3IdeDeltaWire.java` | `7f3c42fbee79a4383b4fb5eec2019ef9216c3283` |
| `M3IdeDeltaWireContractMain.java` | `e4a9885ec9ead288f8509c7b781bbf073699eda5` |

Executed:

~~~sh
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d classes \
  M3IdeDeltaWire.java M3IdeDeltaWireContractMain.java
java -cp classes io.synexia.gradle.quality.M3IdeDeltaWireContractMain
~~~

**Result: PASS**.

Runtime output:

~~~text
PASS: canonical Maven IDE-delta v1 bytes, round trips and malformed input refusals
~~~

The exact Maven v1 source-wire example was byte-identical. The test also
verified canonical UTF-8/Base64URL, multilingual paths, FULL fallback,
duplicate/unsorted changes, escaped and foreign paths, invalid build kinds,
trailing newline, BOM, CRLF and size refusals.

## Code and compatibility boundaries

The Gradle task adapter itself could not be built because the local
environment does not have a Gradle distribution and DNS to GitHub or
services.gradle.org is unavailable. No TestKit run, configuration cache run,
native file-delta test, Maven runtime consumption, JDK17 runtime, Windows,
macOS or full Gradle build is claimed in this receipt.

The source-created workflow runs the standalone Java 17 wire gate on the
JDK17/JDK21 matrix and the original TestKit integration for each platform.
The Maven plugin-source checkout is pinned to merge commit
`b47e9a1e9173502c19de9bb6657a4f88b3267487`, which includes the
Maven #155 descriptor/lint repair rather than the earlier broken #147
module-state source.

This qualification is **only the pure canonical wire contract**.
It does not authorize a merge/release, cache reuse, goal skipping or
source/binary/behavioral compatibility of external language tools.

## Outstanding mandatory gates

- Gradle 9.9.1/TestKit cold/modified/deleted source events on JDK 17 and 21.
- Gradle plugin lazy task registration and configuration-cache reuse.
- Explicitly pinned packet import and foreign-owner refusal with a real task.
- Real Maven M3ExternalBuildWire decode of an original Gradle-produced packet.
- Integration test logs and cross-platform source ownership / validation.

Keep PR #33 draft until these gates have actual receipts.
