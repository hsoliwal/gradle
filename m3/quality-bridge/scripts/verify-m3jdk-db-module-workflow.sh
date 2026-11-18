#!/bin/sh
# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-m3jdk-db-module-access.yml"
test -f "$WORKFLOW" || { echo "M3JDK DB original Maven proof workflow missing" >&2; exit 1; }

require_once() {
    count=$(grep -F -c -- "$1" "$WORKFLOW" || true)
    if [ "$count" -ne 1 ]; then
        printf 'Expected one Maven #220 CI entry (%s), found %s\n' "$1" "$count" >&2
        exit 1
    fi
}

require_once 'ref: c19789716fab23db8b46180f9fd8982bdff298d0'
require_once "test --tests '*M3JdkDbModuleAccessRealEngineTest' --rerun-tasks"
require_once 'mvn -B -ntp -f m3-maven-m3jdk-db/tools/m3-polyglot-maven-plugin/pom.xml verify'
require_once '  pull_request:'
require_once '  workflow_dispatch:'
printf '%s\n' 'PASS: merged Maven #220 original M3JDK database binding pinned in CI'
