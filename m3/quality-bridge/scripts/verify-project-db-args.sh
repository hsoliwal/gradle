#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SOURCE="$HERE/src/main/java/io/synexia/gradle/quality"
TEST="$HERE/src/test/java/io/synexia/gradle/quality"
DEST="$HERE/build/offline-project-db"
mkdir -p "$DEST"
if command -v javac >/dev/null 2>&1; then
  javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d "$DEST" \
    "$SOURCE/M3QualityCommands.java" \
    "$SOURCE/M3PolyglotCommands.java" \
    "$SOURCE/M3ProjectDbCommands.java" \
    "$TEST/M3ProjectDbCommandsContractMain.java"
else
  java -m jdk.compiler/com.sun.tools.javac.Main \
    -source 17 -target 17 -encoding UTF-8 -Xlint:all,-options -Werror -d "$DEST" \
    "$SOURCE/M3QualityCommands.java" \
    "$SOURCE/M3PolyglotCommands.java" \
    "$SOURCE/M3ProjectDbCommands.java" \
    "$TEST/M3ProjectDbCommandsContractMain.java"
fi
java -cp "$DEST" io.synexia.gradle.quality.M3ProjectDbCommandsContractMain
