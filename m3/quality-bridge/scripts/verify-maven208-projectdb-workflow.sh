#!/bin/sh
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Keep the original post-regression source pin and real Gradle consumer gate.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-maven208-projectdb.yml"
test -f "$WORKFLOW" || { echo "Maven #208 project DB workflow missing" >&2; exit 1; }

require_once() {
    count=$(grep -F -c -- "$1" "$WORKFLOW" || true)
    if [ "$count" -ne 1 ]; then
        printf 'Expected one source-pinned workflow entry (%s), found %s\n' "$1" "$count" >&2
        exit 1
    fi
}

require_once "  original-maven208-project-db:"
require_once "java: ['17', '21']"
require_once "ref: fc8e5eee6c17eb2b8553204795a32f020078c9db"
require_once "run: sh m3-maven-project-db-pruned/tools/project-db/scripts/verify-gradle-sources.sh"
require_once "test --tests '*M3ProjectDbPrunedSymlinkRealEngineTest' --rerun-tasks"
require_once "  pull_request:"
require_once "  workflow_dispatch:"

printf '%s\n' "PASS: merged Maven #208 Gradle module-discovery CI pin and TestKit retained"
