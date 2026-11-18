#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Preserve the exact Maven-owned receipt validator and the independent six-case Gradle gate.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-maven224-projectdb-junit.yml"
DOC="$ROOT/m3/quality-bridge/docs/MAVEN224_PROJECTDB_JUNIT_GATE.md"
test -s "$WORKFLOW" || { echo 'missing Maven 224 acceptance workflow' >&2; exit 1; }
test -s "$DOC" || { echo 'missing Maven 224 acceptance provenance' >&2; exit 1; }
require() {
    grep -Fq -- "$1" "$WORKFLOW" || { echo "Maven 224 acceptance guard missing: $1" >&2; exit 1; }
}
require 'ref: 77f3b7fbbf847c28d591973bb153b00b5237712e'
require '10bd8356e383e53544f232df0834ef084871d26760954cd3076ee85db90c4573'
require 'm3-maven-224/tools/m3-polyglot-maven-plugin/pom.xml install'
require 'm3-maven-224/tools/project-db/pom.xml verify'
require 'M3_POLYGLOT_JAR='
require 'M3_PROJECT_DB_JAR='
require "--tests 'io.synexia.gradle.quality.M3ProjectDbRealEngineTest'"
require "--tests 'io.synexia.gradle.quality.M3ProjectDbGradleNativeRealEngineTest'"
require "--tests 'io.synexia.gradle.quality.M3ProjectDbTaskTest'"
require '--rerun-tasks'
require 'sh m3-maven-224/tools/project-db/scripts/verify-gradle-testkit-results.sh m3/quality-bridge/build/test-results/test'
require 'java:'
require "['17', '21']"
if grep -Fq -- "--tests '*M3ProjectDb*'" "$WORKFLOW"; then
    echo 'Maven 224 gate must select three known suites; wildcard includes extra Gradle project DB fixtures' >&2
    exit 1
fi
test -s "$ROOT/m3/quality-bridge/recipes/enable-m3-quality-bridge.yml" ||
    { echo 'missing canonical OpenRewrite consumer recipe' >&2; exit 1; }
printf '%s\n' 'PASS: Maven 224 original six-case JUnit gate, exact suite selectors and recipe retained'
