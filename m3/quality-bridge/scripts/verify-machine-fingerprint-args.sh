#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SOURCE="$HERE/src/main/java/io/synexia/gradle/quality"
TEST="$HERE/src/test/java/io/synexia/gradle/quality"
DEST="$HERE/build/offline-machine-fingerprints"
mkdir -p "$DEST"
if command -v javac >/dev/null 2>&1; then
  compile() { javac --release 17 -encoding UTF-8 -Xlint:all -Werror "$@"; }
else
  # Syntax/bytecode compatibility only, not a Java 17 API closure check.
  compile() { java -m jdk.compiler/com.sun.tools.javac.Main \
    -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror "$@"; }
fi
compile -d "$DEST" \
  "$SOURCE/M3QualityCommands.java" \
  "$SOURCE/M3PolyglotCommands.java" \
  "$SOURCE/M3MachineFingerprintCommands.java" \
  "$TEST/M3MachineFingerprintArgsContractMain.java"
java -cp "$DEST" io.synexia.gradle.quality.M3MachineFingerprintArgsContractMain
