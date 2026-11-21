#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Preserve source pin, no-shell CLI, exact receipt and two real-engine TestKit suites.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../../.." && pwd)
FLOW="$ROOT/.github/workflows/m3-gradle-maven255-compile-db.yml"
MAIN="$ROOT/m3/quality-bridge/src/main/java/io/synexia/gradle/quality/M3LlvmCompileDbCommands.java"
TASK="$ROOT/m3/quality-bridge/src/main/java/io/synexia/gradle/quality/M3LlvmCompileDbTask.java"
for f in "$FLOW" "$MAIN" "$TASK"; do test -s "$f" || exit 1; done
require() { grep -Fq -- "$1" "$FLOW" || { echo "missing source-owned CI requirement: $1" >&2; exit 1; }; }
require 'ref: f403544494810fd78fc3f00b8c790935bbebfeaf'
require "java: ['17', '21']"
require 'M3LlvmCompileDbTaskTest'
require 'M3LlvmCompileDbRealEngineTest'
require 'M3LlvmCompileDbArgsContractMain'
require '--tests'
grep -Fq -- 'register("m3LlvmCompileDb"' "$ROOT/m3/quality-bridge/src/main/java/io/synexia/gradle/quality/M3QualityPlugin.java"
grep -Fq -- '"llvm-compile-db"' "$MAIN"
require 'mvn -B -ntp'
require 'sha256sum'
require 'M3_MAVEN_COMPILEDB_JAR='
require 'M3_MAVEN_COMPILEDB_FIXTURES='
require 'M3_LLVM_HOME='
grep -Fq -- 'M3-LLVM-COMPILE-DB-V1' "$MAIN"
grep -Fq -- '@UntrackedTask' "$TASK"
if grep -Fq -- 'dependsOn(' "$TASK"; then
    echo 'compile DB task must not auto-attach to a Gradle build graph' >&2
    exit 1
fi
printf '%s\n' 'PASS: original Maven #255 compile DB source-pin and TestKit gate preserved'
