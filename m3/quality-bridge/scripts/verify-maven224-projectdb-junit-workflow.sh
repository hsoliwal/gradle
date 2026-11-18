#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Preserve Maven #224's six cases and Maven #226/#230's secure Java17 XML verifier.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-maven224-projectdb-junit.yml"
DOC="$ROOT/m3/quality-bridge/docs/MAVEN224_PROJECTDB_JUNIT_GATE.md"
test -s "$WORKFLOW" || { echo 'missing Maven 224 acceptance workflow' >&2; exit 1; }
test -s "$DOC" || { echo 'missing Maven 224 acceptance provenance' >&2; exit 1; }
require() {
    grep -Fq -- "$1" "$WORKFLOW" || { echo "Maven 224 acceptance guard missing: $1" >&2; exit 1; }
}
require 'ref: d3385d6ca33f4ac7bdc2a9f3b2d02504b5976bed'
require '6b1a98534333c5563469c9ee83434b032d7f792d7cc7b67d727d22804db2456f'
require '0f2d667d6cafc6cf5463775177f57e71a71221173933c320b28fbd675945c979'
require 'm3-maven-230 rev-parse HEAD'
require 'm3-maven-230/tools/project-db/scripts/M3GradleTestKitReceiptMain.java'
require 'm3-maven-230/tools/m3-polyglot-maven-plugin/pom.xml install'
require 'm3-maven-230/tools/project-db/pom.xml verify'
require 'M3_POLYGLOT_JAR='
require 'M3_PROJECT_DB_JAR='
require "--tests 'io.synexia.gradle.quality.M3ProjectDbRealEngineTest'"
require "--tests 'io.synexia.gradle.quality.M3ProjectDbGradleNativeRealEngineTest'"
require "--tests 'io.synexia.gradle.quality.M3ProjectDbTaskTest'"
require '--rerun-tasks'
require 'sh m3-maven-230/tools/project-db/scripts/verify-gradle-testkit-results.sh m3/quality-bridge/build/test-results/test'
require 'java:'
require "['17', '21']"
if grep -Fq -- "--tests '*M3ProjectDb*'" "$WORKFLOW"; then
    echo 'Maven 224 gate must select three known suites; wildcard includes extra Gradle project DB fixtures' >&2
    exit 1
fi
test -s "$ROOT/m3/quality-bridge/recipes/enable-m3-quality-bridge.yml" ||
    { echo 'missing canonical OpenRewrite consumer recipe' >&2; exit 1; }
printf '%s\n' 'PASS: Maven 224 six cases + Maven 230 secure Java17 receipt parser, exact selectors and recipe retained'
