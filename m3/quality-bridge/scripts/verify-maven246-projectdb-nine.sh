#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Preserve original source-pinned Maven nine-case JUnit admission and separate six-case history.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../../.." && pwd)
FLOW="$ROOT/.github/workflows/m3-gradle-maven246-projectdb-nine.yml"
HIST="$ROOT/.github/workflows/m3-gradle-maven224-projectdb-junit.yml"
RECIPE="$ROOT/m3/quality-bridge/recipes/enable-m3-quality-bridge.yml"
test -s "$FLOW" && test -s "$HIST" || { echo 'missing project DB acceptance history' >&2; exit 1; }
require() { grep -Fq -- "$1" "$FLOW" || { echo "missing Maven 246 nine-case contract: $1" >&2; exit 1; }; }
require 'ref: 5942487fbfa7ad7fbc7313cecf12958f823b6262'
require "java: ['17', '21']"
require 'M3GradleTestKitReceiptMain.java'
require 'verify-gradle-junit-modes.sh'
require 'M3_PROJECT_DB_JAR='
require 'M3_POLYGLOT_JAR='
require 'M3_PRUNED_PROJECT_DB_JAR='
require 'M3_PRUNED_POLYGLOT_JAR='
require 'M3ProjectDbRealEngineTest'
require 'M3ProjectDbGradleNativeRealEngineTest'
require 'M3ProjectDbTaskTest'
require 'M3ProjectDbPrunedSymlinkRealEngineTest'
require '--rerun-tasks'
require 'current-nine'
require 'actions/upload-artifact@v4'
grep -Fq -- 'd3385d6ca33f4ac7bdc2a9f3b2d02504b5976bed' "$HIST" ||
  { echo 'historical secure six-case Maven source pin was lost' >&2; exit 1; }
test -s "$RECIPE" || { echo 'missing existing canonical opt-in OpenRewrite recipe' >&2; exit 1; }
printf '%s\n' 'PASS: immutable Maven 246 current-nine and preserved Maven 230 six-case history'
