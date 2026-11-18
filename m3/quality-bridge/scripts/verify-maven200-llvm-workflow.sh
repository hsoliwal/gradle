#!/bin/sh
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-maven200-llvm-selection.yml"
RECIPE="$ROOT/m3/quality-bridge/recipes/enable-m3-llvm-provider-bridge.yml"
test -f "$WORKFLOW" || { echo "Maven #200 original LLVM proof lane missing" >&2; exit 1; }
test -f "$RECIPE" || { echo "reviewed OpenRewrite opt-in recipe missing" >&2; exit 1; }

require_once() {
    count=$(grep -F -c -- "$1" "$WORKFLOW" || true)
    if [ "$count" -ne 1 ]; then
        printf 'Expected one Maven #200 CI entry (%s), found %s\n' "$1" "$count" >&2
        exit 1
    fi
}

require_once 'ref: 0249d0fd050ac89c89f7a0f8af1328cb944f076c'
require_once 'mvn -B -ntp -f m3-maven-llvm200/tools/m3-polyglot-maven-plugin/pom.xml verify'
require_once "test --tests '*M3Llvm200InstallationRealEngineTest' --rerun-tasks"
require_once '  pull_request:'
require_once '  workflow_dispatch:'
require_once 'command -v clang++'
printf '%s\n' 'PASS: merged Maven #200 original LLVM installation proof pinned'
