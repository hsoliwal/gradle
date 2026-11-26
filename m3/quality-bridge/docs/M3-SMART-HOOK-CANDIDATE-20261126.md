<!-- SPDX-License-Identifier: Apache-2.0 -->
# Maven #315 candidate / #317 merged-to-branch: Gradle smart advice revalidation

**Source custody (2026-11-26):** Maven `master@62499c2051a6152f8e4951728264efb4fd5b1fb8` still contains the
**older** `BuildHookMain`; Maven [#317](https://github.com/hsoliwal/maven/pull/317)
merged into **draft** [#315](https://github.com/hsoliwal/maven/pull/315), not into `master`.
The candidate hook source is frozen to `fcb80e8007f47d74c5f1b6f18200b53b2a485d12`.
Do not report #317 as released to Maven/Gradle master.

The original Gradle `m3PreBuildAdvice` task already delegates to Maven's BuildHookMain through a
SHA-256-pinned JAR and retains its native Gradle configuration-cache semantics. This batch changes
**only** its existing TestKit owner: no alternate receipt grammar, source scanner, summarizer,
provider, engine, task, default task dependency or cache admission is added.

## Exactly three opt-in candidate checks

- A malformed source-bound preparation report refuses **before** an explicitly configured
  Java-17-compatible CPU summarizer writes a marker; its report must say
  `summaryStatus=blocked-admission`.
- After a valid source-bound receipt, the summarizer edits the original Java bytes to an
  **equal-length** alternative and restores the original mtime; a second original Maven evidence
  check must refuse it and clear the model candidate. Source length/mtime equality cannot license
  reuse.
- Unmodified source and receipt must allow the same summarizer and emit the original
  `source-bound` / `available` / `disabled` statuses without starting any model.

Synthetic source-bound receipts are inherited from
`M3SmartReviewRealEngineTest.writeSyntheticReceipts`: they are **test data**, not
compiler/behavior/JIT proofs. The exact companion [Synexia recipe proposal](https://github.com/hsoliwal/com.synexia/pull/19428)
contains the complete Git preimage and the expected postimage. The Java test postimage must
equal Git blob `1a9b993f2ab9470fc9e90788fd579860010097ec`. No new test is active in
the older Maven CI lane because all three require `M3_SMART_REVALIDATION_CANDIDATE_JAR`.
A separate source-pinned JDK17/21 job checks out the candidate and demands that **all three**
exact Gradle TestKit methods run with no JUnit skip, error or failure.

## Remaining gates

1. Actual OpenRewrite 8.90.4 or reviewed equivalent SDK replay of the recipe, then a fixed-point
   second replay and source SHA-256/structural/logic fingerprint verification.
2. Maven #315 must satisfy its own branch provenance, `mvn verify`, cross-platform and native
   provider qualification; #317 being merged into #315 is not sufficient.
3. Hosted JDK17/21 TestKit XML artifacts must prove three executed zero-skip passes, with
   a separate review of warning/error output and native process retirement.
4. Human code/license/DCO review and explicit integration of Maven #315 into `master`;
   only then consider updating Gradle's production Maven pin.

Do not automatically enable inference, rewrite any source, attach build/check, or trust
M3DB/Gradle build-cache reuse from an advisory report.
