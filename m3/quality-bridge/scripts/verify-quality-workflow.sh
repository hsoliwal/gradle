#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Fail if a merge or upstream sync accidentally loses a source-pinned
# Maven/Gradle cross-fork qualification lane.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-quality-bridge.yml"
test -f "$WORKFLOW" || { echo "M3 quality workflow is missing" >&2; exit 1; }

require_once() {
    count=$(grep -F -c -- "$1" "$WORKFLOW" || true)
    if [ "$count" -ne 1 ]; then
        printf 'Expected one workflow contract entry (%s), found %s\n' "$1" "$count" >&2
        exit 1
    fi
}

require_once "ref: b47e9a1e9173502c19de9bb6657a4f88b3267487"
require_once "ref: 26efa14266288f8cb11c6232bbdc8ea94ae26134"
require_once "test --tests '*M3PolyglotRealEngineTest' --rerun-tasks"
require_once "test --tests '*M3PolyglotCacheRealEngineTest' --rerun-tasks"
require_once "test --tests '*M3PolyglotStateAliasesRealEngineTest' --rerun-tasks"
require_once "test --tests '*M3MachineFingerprintRealEngineTest' --rerun-tasks"
require_once "test --tests '*M3NormalizeAnalyzerXmlTaskTest' --rerun-tasks"
require_once "test --tests '*M3CompilerDiagnosticsRealEngineTest' --rerun-tasks"
require_once "run: sh m3/quality-bridge/scripts/verify-ide-delta.sh"
require_once "run: sh m3/quality-bridge/scripts/verify-polyglot-cache-args.sh"
require_once "run: sh m3/quality-bridge/scripts/verify-portable-backends.sh"
require_once "run: sh m3/quality-bridge/scripts/verify-machine-fingerprint-args.sh"
require_once "run: sh m3/quality-bridge/scripts/verify-compiler-diagnostics-args.sh"
require_once "ref: 64e5ce257c46207eac32407f00979cfc4a79767e"
require_once "test --tests '*M3ProjectDbRealEngineTest' --rerun-tasks"
require_once "test --tests '*M3ProjectDbGradleNativeRealEngineTest' --rerun-tasks"
require_once "run: sh m3/quality-bridge/scripts/verify-project-db-args.sh"
require_once "  push:"
require_once "  pull_request:"
require_once "  workflow_dispatch:"

# Newer Maven #184/#188 producer qualification is deliberately independent
# of the original Maven #149 cache and the earlier converter source pins.
require_once "ref: f92096f8d3eb135d28cf698335a4423ef9179e9d"

# Maven #198 is an independent, merge-pinned LLVM SPI producer. A host Clang
# smoke is mandatory in CI but is not proof of the user's LLVM fork ABI.
require_once "ref: 83284eb8de8758fa9ffa84ca6c2d764e67c183c8"
require_once "test --tests '*M3LlvmProviderRealEngineTest' --rerun-tasks"
# Maven #206 optional storage adapters are independently source-pinned;
# missing native drivers must never silently select the embedded backend.
require_once "  m3-maven206-portable-crossfork:"
require_once "ref: fee3ca7cd8ca0b698d7308103836e99074479950"
require_once "test --tests '*M3PortableEnginesRealEngineTest' --rerun-tasks"

# Maven #203 is a separate optional LLVM/MLIR/LLD provider artifact; its
# ServiceLoader and protocol are not covered by the older compiler-only #198.
require_once "ref: 3441dc1e91a2835b7b6bca608ffb9676565d9eff"
require_once "run: sh m3/quality-bridge/scripts/verify-llvm-tools-inventory.sh"
require_once "test --tests '*M3LlvmToolsProviderRealEngineTest' --rerun-tasks"
require_once "mvn -B -ntp -f m3-maven-llvm-tools/tools/m3-llvm-tools-provider/pom.xml verify"

printf '%s\n' "PASS: source-pinned M3 Gradle/Maven CI lanes retained"
