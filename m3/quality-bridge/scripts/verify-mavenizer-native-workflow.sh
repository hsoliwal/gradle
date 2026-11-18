#!/bin/sh
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-mavenizer-native.yml"
test -f "$WORKFLOW" || { echo "Mavenizer native proof workflow missing" >&2; exit 1; }

require_once() {
    count=$(grep -F -c -- "$1" "$WORKFLOW" || true)
    if [ "$count" -ne 1 ]; then
        printf 'Expected one Maven #212 CI entry (%s), found %s\n' "$1" "$count" >&2
        exit 1
    fi
}

require_once 'ref: 95bc17f8212350af1632e16990e985f8a2ee72cb'
require_once "test --tests '*M3MavenizerNativePhasesRealEngineTest' --rerun-tasks"
require_once 'mvn -B -ntp -f m3-maven-native-mavenizer/tools/m3-polyglot-maven-plugin/pom.xml verify'
require_once '  pull_request:'
require_once '  workflow_dispatch:'
printf '%s\n' 'PASS: original Maven #212 Rebar3/Composer cross-fork lane retained'
