#!/bin/sh
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-maven222-objcxx-pin.yml"
RECIPE="$ROOT/m3/quality-bridge/recipes/enable-m3-llvm-provider-bridge.yml"
test -f "$WORKFLOW" || { echo "merged Maven #222 Objective-C++ workflow missing" >&2; exit 1; }
test -f "$RECIPE" || { echo "source-owned LLVM OpenRewrite opt-in recipe missing" >&2; exit 1; }

require_once() {
    count=$(grep -F -c -- "$1" "$WORKFLOW" || true)
    if [ "$count" -ne 1 ]; then
        printf 'Expected one Maven #222 CI entry (%s), found %s\n' "$1" "$count" >&2
        exit 1
    fi
}

require_once 'ref: 36bc166d236950a5b220236516f5da90cc044571'
require_once 'mvn -B -ntp -f m3-maven-objcxx222/tools/m3-polyglot-maven-plugin/pom.xml verify'
require_once "test --tests '*M3Llvm222ObjectiveCppRealEngineTest' --rerun-tasks"
require_once '  pull_request:'
require_once '  workflow_dispatch:'
require_once '          command -v clang++'
printf '%s\n' 'PASS: original Maven #222 Objective-C++ byte selection source pinned'
