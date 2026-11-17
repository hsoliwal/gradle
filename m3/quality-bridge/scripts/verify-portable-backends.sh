#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SOURCE="$HERE/src/main/java/io/synexia/gradle/quality"
TEST="$HERE/src/test/java/io/synexia/gradle/quality"
DEST="$HERE/build/offline-portable-backends"
mkdir -p "$DEST"
if command -v javac >/dev/null 2>&1; then
  compile() { javac --release 17 -encoding UTF-8 -Xlint:all -Werror "$@"; }
else
  # Reduced images may omit the launcher and ct.sym, but retain jdk.compiler.
  # This fallback checks syntax and target bytecode, not the Java 17 API surface.
  compile() { java -m jdk.compiler/com.sun.tools.javac.Main \
    -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror "$@"; }
fi
compile -d "$DEST" \
  "$SOURCE/M3QualityCommands.java" \
  "$SOURCE/M3PolyglotCommands.java" \
  "$TEST/M3PortableStateArgsContractMain.java"
java -cp "$DEST" io.synexia.gradle.quality.M3PortableStateArgsContractMain
