#!/bin/sh
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SOURCE="$HERE/src/main/java/io/synexia/gradle/quality"
TEST="$HERE/src/test/java/io/synexia/gradle/quality"
DEST="$HERE/build/offline-m3jdk-db-module-access"
mkdir -p "$DEST"
if command -v javac >/dev/null 2>&1; then
    compile() { javac --release 17 -encoding UTF-8 -Xlint:all -Werror "$@"; }
else
    # Reduced JDK images lack the javac launcher/ct.sym, not jdk.compiler.
    # This fallback checks Java 17 syntax and bytecode, not --release APIs.
    compile() { java -m jdk.compiler/com.sun.tools.javac.Main \
        -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror "$@"; }
fi
compile -d "$DEST" \
    "$SOURCE/M3QualityCommands.java" \
    "$SOURCE/M3PolyglotCommands.java" \
    "$TEST/M3DbModuleAccessArgsContractMain.java"
java -cp "$DEST" io.synexia.gradle.quality.M3DbModuleAccessArgsContractMain
