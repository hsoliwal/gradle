#!/bin/sh
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Java 17 wire qualification needs neither Gradle nor Maven distributions.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CLASSES="$ROOT/build/llvm-tools-inventory-offline"
mkdir -p "$CLASSES"

if command -v javac >/dev/null 2>&1; then
    javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d "$CLASSES" \
        "$ROOT/src/main/java/io/synexia/gradle/quality/M3ProviderInventoryV1.java" \
        "$ROOT/src/test/java/io/synexia/gradle/quality/M3ProviderInventoryContractMain.java"
else
    # Minimal runtime images can omit ct.sym, so --release is unavailable;
    # hosted CI uses real javac --release 17 above for the API guard.
    java -m jdk.compiler/com.sun.tools.javac.Main --source 17 --target 17 -encoding UTF-8 \
        -Xlint:all,-options -Werror -d "$CLASSES" \
        "$ROOT/src/main/java/io/synexia/gradle/quality/M3ProviderInventoryV1.java" \
        "$ROOT/src/test/java/io/synexia/gradle/quality/M3ProviderInventoryContractMain.java"
fi
java -cp "$CLASSES" io.synexia.gradle.quality.M3ProviderInventoryContractMain
