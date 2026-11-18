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

## MOP SDK cross-repository agent read gate (2026-11-18)
<!-- MOP-AGENT-2026-11-18-1 -->
Before any source analysis, refactor, PR, merge, licensing decision, or history checkpoint, also read [AGENT.md](AGENT.md). It governs bidirectional atomization/patternization, documented contract-sealed atoms, additive best-of-best API supersets, complete available-history loss checks, dated last-verified proof, Apache-2.0 eligibility and upstream rights. Existing stronger project-specific and locked-canon constraints remain in force. Installation of this pointer does not establish that a source or historical audit has run.
