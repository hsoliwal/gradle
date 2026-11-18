#!/bin/sh
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Isolate the Maven #211 producer source pin from older Maven ABI lanes.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-eclipse-descriptors.yml"
test -f "$WORKFLOW" || { echo "Eclipse descriptor proof workflow missing" >&2; exit 1; }

require_once() {
    count=$(grep -F -c -- "$1" "$WORKFLOW" || true)
    if [ "$count" -ne 1 ]; then
        printf 'Expected one Maven #211 Eclipse CI entry (%s), found %s\n' "$1" "$count" >&2
        exit 1
    fi
}

require_once 'ref: 2b4ea06f34d785b78a7811ca59cf6d24efb02287'
require_once "test --tests '*M3EclipseNativeDescriptorsRealEngineTest' --rerun-tasks"
require_once 'mvn -B -ntp -f m3-maven-eclipse-descriptors/tools/m3-polyglot-maven-plugin/pom.xml verify'
require_once '  pull_request:'
require_once '  workflow_dispatch:'
printf '%s\n' 'PASS: original Maven #211 Eclipse descriptor cross-fork lane retained'
