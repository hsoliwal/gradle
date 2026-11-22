#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Fail closed on loss of Java17/21, immutable Maven #236 or required real driver coverage.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../../.." && pwd)
FLOW="$ROOT/.github/workflows/m3-gradle-maven236-derby.yml"
TEST="$ROOT/m3/quality-bridge/src/test/java/io/synexia/gradle/quality/M3DerbyRealEngineTest.java"
RECIPE="$ROOT/m3/quality-bridge/recipes/enable-m3-polyglot-state-bridge.yml"
for f in "$FLOW" "$TEST" "$RECIPE"; do test -s "$f" || exit 1; done
require() { grep -Fq -- "$1" "$FLOW" || { echo "missing Maven 236 guard: $1" >&2; exit 1; }; }
require 'ref: 2bc1468e9f318724b01ae4d51b3078aa2a09a6d3'
require "java: ['17', '21']"
require '-Pm3-derby verify'
require 'm3-derby-owner.log'
require 'derby-10.16.1.1.jar'
require 'derbytools-10.16.1.1.jar'
require 'JAVA_TOOL_OPTIONS:'
require 'M3_REQUIRE_DERBY_REAL_ENGINE:'
require 'M3_MAVEN_DERBY_ENGINE_JAR='
require 'M3_DERBY_DRIVER_DIRECTORY='
require '--tests'
require 'io.synexia.gradle.quality.M3DerbyRealEngineTest'
require 'four real non-skipped'
require 'assert len(cases) == 4'
require 'sha256sum'
require 'actions/upload-artifact@v4'
grep -Fq -- "stateBackend.set('ext-derby-v1')" "$TEST"
grep -Fq -- 'providerJarSha256.put(' "$TEST"
grep -Fq -- 'Enable the opt-in M3 polyglot state bridge' "$RECIPE" ||
  grep -Fq -- 'io.synexia.gradle.recipe.EnableM3PolyglotStateBridge' "$RECIPE"
printf '%s\n' 'PASS: immutable Maven 236 Derby runtime, JDK matrix, four-case TestKit and recipe guard'
