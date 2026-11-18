#!/bin/sh
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Fail closed if an upstream merge loses Maven #210's immutable source proof.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-llvm-evidence.yml"
test -f "$WORKFLOW" || { echo "M3 LLVM evidence CI lane missing" >&2; exit 1; }

require_once() {
    count=$(grep -F -c -- "$1" "$WORKFLOW" || true)
    if [ "$count" -ne 1 ]; then
        printf 'Expected exactly one LLVM evidence CI entry (%s), found %s\n' "$1" "$count" >&2
        exit 1
    fi
}

require_once 'ref: 78848ef9a52fbc1ae34032bd46b3d057b5f1d7fd'
require_once "run: sh m3/quality-bridge/scripts/verify-llvm-evidence-args.sh"
require_once "test --tests '*M3LlvmToolchainEvidenceRealEngineTest' --rerun-tasks"
require_once 'mvn -B -ntp -f m3-maven-llvm-evidence/tools/m3-llvm-tools-provider/pom.xml verify'
require_once '  pull_request:'
require_once '  workflow_dispatch:'
printf '%s\n' 'PASS: Maven #210 source-pinned Gradle evidence lane retained'
