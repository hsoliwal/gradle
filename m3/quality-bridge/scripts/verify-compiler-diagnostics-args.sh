#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SOURCE="$HERE/src/main/java/io/synexia/gradle/quality"
TEST="$HERE/src/test/java/io/synexia/gradle/quality"
DEST="$HERE/build/offline-compiler-diagnostics"
mkdir -p "$DEST"
if command -v javac >/dev/null 2>&1; then
  compile() { javac --release 17 -encoding UTF-8 -Xlint:all -Werror "$@"; }
else
  # This reduced JDK has no javac launcher/ct.sym; check 17 syntax/bytecode, not full API closure.
  compile() { java -m jdk.compiler/com.sun.tools.javac.Main \
    -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror "$@"; }
fi
compile -d "$DEST" \
  "$SOURCE/M3QualityCommands.java" \
  "$SOURCE/M3AnalyzerAdapterCommands.java" \
  "$TEST/M3CompilerDiagnosticsArgsContractMain.java"
java -cp "$DEST" io.synexia.gradle.quality.M3CompilerDiagnosticsArgsContractMain
