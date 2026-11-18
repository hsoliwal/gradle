#!/bin/sh
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CLASSES="$ROOT/build/llvm-evidence-args-offline"
mkdir -p "$CLASSES"
SOURCE="$ROOT/src/main/java/io/synexia/gradle/quality"
TEST="$ROOT/src/test/java/io/synexia/gradle/quality"

if command -v javac >/dev/null 2>&1; then
    javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d "$CLASSES" \
        "$SOURCE/M3QualityCommands.java" "$SOURCE/M3LlvmToolchainEvidenceCommands.java" \
        "$TEST/M3LlvmToolchainEvidenceArgsContractMain.java"
else
    # Minimal JDKs without ct.sym cannot use --release; CI above verifies API 17.
    java -m jdk.compiler/com.sun.tools.javac.Main --source 17 --target 17 \
        -encoding UTF-8 -Xlint:all,-options -Werror -d "$CLASSES" \
        "$SOURCE/M3QualityCommands.java" "$SOURCE/M3LlvmToolchainEvidenceCommands.java" \
        "$TEST/M3LlvmToolchainEvidenceArgsContractMain.java"
fi
java -cp "$CLASSES" io.synexia.gradle.quality.M3LlvmToolchainEvidenceArgsContractMain
