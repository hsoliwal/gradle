# Agent Instructions

## Priority 0 — universal additive superset, full-history merge and last-checked rule (2026-11-18)

Preserve the **backward-compatible optimized superset** of all applicable accepted upstream/fork capabilities, source/binary/public APIs, behavior, fixes, platform/runtime contracts and previous qualified optimizations. Compare candidate algorithms and branches on differential correctness, worst-case complexity, CPU/heap/native cost, scalability and maintainability; choose the best **proven** implementation, allowing compatible additive API extensions but no silent removal. Before merge, inspect the earliest reachable ancestors across every relevant branch/tag, old merged/closed/reverted PR, merge base and both merge parents, to identify bad commits, lost code, removed APIs and lost optimizations; never rebase `develop`. Persist last-checked date, precise ref/head SHA, scope, oldest ancestor checked, last verified SHA, branch/PR coverage, loss findings and PENDING/PARTIAL/VERIFIED/HOLD in the canonical Synexia checkpoint register. An observed HEAD is **not** an exhaustive history audit. First-party separable new work prefers Apache-2.0; preserve original upstream and derivative license/NOTICE obligations. LLM atomization/patternization does not by itself change copyright or code licensing. Source-changing work follows canonical Synexia Maven/OpenRewrite recipe/historical-proof gates; public product forks remain clean postimage-only receivers. Policy and initial explicitly **PENDING** repository inventory: [Synexia PR #18764](https://github.com/hsoliwal/com.synexia/pull/18764).


Guidance for AI coding agents of any vendor working in the Gradle repository.
Human contributors should start from [CONTRIBUTING.md](CONTRIBUTING.md).

## Before you start

- Follow the [code change guidelines](CONTRIBUTING.md#code-change-guidelines) as well as the relevant topical contributing guidelines:
  - For information about how to to add suggestions to error messages, see [ErrorMessages.md](contributing/ErrorMessages.md).
  - For JavaDoc style guidelines, see [JavadocStyleGuide.md](contributing/JavadocStyleGuide.md).
  - For guidelines on nullability and related annotations, see [Nullability.md](contributing/Nullability.md).
  - For information on writing tests for Gradle, see [Testing.md](contributing/Testing.md).


## Superset merge and history-audit rule (2026-11-18)

Read [.github/MERGE_RULE.md](.github/MERGE_RULE.md) before changing an existing implementation or integrating any branch or PR.
Preserve the supported additive API/behavior superset and select implementations by correctness, predictability, measured performance, then elegance.
Audit all reachable history back to every root, all branches/tags and all PR states for lost code or regressions; preserve append-only history.
Record actual UTC coverage and remaining work in [.github/HISTORY_AUDIT.tsv](.github/HISTORY_AUDIT.tsv); policy installation is not a completed source-history audit.
Prefer verified Apache-2.0 sources and independent implementations while preserving all existing third-party rights; LLM rewriting does not remove license obligations.
Detailed private recipe/proof records remain in canonical Synexia; the compact local policy and checkpoint make the rule discoverable here.
<!-- MERGE-SUPERSET-HISTORY-20261118-2 -->
