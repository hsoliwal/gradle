#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Static source-custody guard; native Maven/HSQLDB acceptance executes in CI.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-maven227-hsqldb.yml"
TEST="$ROOT/m3/quality-bridge/src/test/java/io/synexia/gradle/quality/M3HsqlDbRealEngineTest.java"
test -s "$WORKFLOW" && test -s "$TEST" || { echo 'missing Maven 227 HSQLDB evidence lane' >&2; exit 1; }
require() {
    grep -Fq -- "$1" "$WORKFLOW" || { echo "Maven 227 source guard missing: $1" >&2; exit 1; }
}
require 'ref: 738eb1b78725d7f355b9f4fdb6d34dbad0024963'
require 'm3-maven-227 rev-parse HEAD'
require 'm3-maven-227/tools/m3-polyglot-maven-plugin/pom.xml install'
require 'm3-maven-227/tools/m3-polyglot-maven-plugin/pom.xml -Pm3-hsqldb verify'
require '-Dartifact=org.hsqldb:hsqldb:2.7.4'
require 'M3_POLYGLOT_HSQLDB_JAR='
require 'M3_HSQLDB_DRIVER_JAR='
require "M3_REQUIRE_HSQLDB_REAL_ENGINE: '1'"
require "--tests 'io.synexia.gradle.quality.M3HsqlDbRealEngineTest' --rerun-tasks"
require "['17', '21']"
require "('tests', '3')"
require "('skipped', '0')"
require "('failures', '0')"
require "('errors', '0')"
require 'name: m3-gradle-maven227-hsqldb-jdk-'
grep -Fq '"hsqldb".equals(backend)' \
    "$ROOT/m3/quality-bridge/src/main/java/io/synexia/gradle/quality/M3PolyglotCommands.java" ||
    { echo 'Maven 227 HSQLDB backend must be opt-in only' >&2; exit 1; }
test -s "$ROOT/m3/quality-bridge/recipes/enable-m3-polyglot-state-bridge.yml" ||
    { echo 'missing canonical Maven polyglot state OpenRewrite recipe' >&2; exit 1; }
printf '%s\n' 'PASS: Maven 227 original HSQLDB selection, exact source, real driver and three-test Java17/21 gate'
