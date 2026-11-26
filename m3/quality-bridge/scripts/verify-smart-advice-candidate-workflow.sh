#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# #317 merged to the draft #315 branch, not to Maven master.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
WORKFLOW="$ROOT/.github/workflows/m3-gradle-smart-advice-candidate.yml"
test -f "$WORKFLOW" || { echo "M3 candidate advice workflow missing" >&2; exit 1; }
once() {
    count=$(grep -F -c -- "$1" "$WORKFLOW" || true)
    [ "$count" -eq 1 ] || {
        printf 'Expected exactly one candidate gate (%s), observed %s\n' "$1" "$count" >&2
        exit 1
    }
}
once 'ref: fcb80e8007f47d74c5f1b6f18200b53b2a485d12'
once "java: ['17', '21']"
once "test --tests"
once "candidateRefusesInvalidPreparationBeforeLaunchingSummarizer'"
once "candidateRejectsSameSizeTimestampRestoredSourceDriftAfterSummary'"
once "candidateAcceptsUnchangedPreparationAfterSummary'"
once "M3_SMART_REVALIDATION_CANDIDATE_JAR:"
once "  workflow_dispatch:"
once "  pull_request:"
printf '%s\n' 'PASS: exact Maven #315/#317 candidate source and three Gradle TestKit gates retained'
