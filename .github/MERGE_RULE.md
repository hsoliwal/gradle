# Merge rule: superset, best of best, additive API

This rule applies to every pull request in this repository and to every merge between branches. It governs
conflict resolution. A PR can remain open or draft until its mandatory evidence and tests pass;
conflicts alone never justify discarding a compatible contribution.

1. **Superset.** The merged result has every capability of both sides: behaviour, tests, documentation and
   data. Nothing that either side added is dropped.
2. **Best of best.** When both sides implement the same behaviour, keep one implementation: the one that
   wins on evidence. That means it passes both sides' tests, measures faster or allocates less, and is the
   simpler of the two. Port any distinct behaviour of the other implementation into the winner, so that no
   second copy of the same code remains.
3. **Additive API.** Public API is only ever added:
   - Both sides' public types, members and options stay.
   - If two signatures clash (same name and erasure, or the same option with different meanings), keep the
     one already on the base branch and add the other under a distinct name, an overload, a default method
     or an adapter.
   - Deprecate rather than delete. Never change an existing public signature incompatibly.
4. **Data, ledgers and indexes.** Preserve all distinct valid records, reconciling duplicates or collisions with provenance; do not silently discard data. Regenerate generated files
   (indexes, checksums, provenance) with their generator instead of merging them by hand.
5. **Already contained.** When the base branch already contains a pull request's work in another form,
   land the pull request with a merge commit whose files keep the base's code plus anything the pull request
   adds that the base lacks. Its history is kept and no duplicate implementation is added.
6. **Proof.** Both sides' tests pass on the merged tree, compared with each side's own result. The merge
   record states what was not run, for example a native build that needs another platform.
7. **History audit, every time.** Before and after merging, examine the git history, the open and closed pull
   requests, and every branch, to detect bad commits and lost code:
   - mass deletions, empty or truncated trees;
   - reverts of other work;
   - merges that dropped lines one side added (a 3-way loss check per merge);
   - branches and closed pull requests whose commits never reached the default branch.
   Restore every genuine loss by this same rule, in a commit that names the commit or pull request that lost
   it. Record deliberate removals, with their reason, in the same commit.
8. **All the way back, then record the last check.**
   - The first audit covers the whole history: every merge since the first commit, every branch, and every
     closed pull request.
   - Each audit appends one row to `.github/HISTORY_AUDIT.tsv` with these columns:
     `checked_at` (UTC), `ref`, `last_checked_commit`, `merges_audited`, `branches_checked`,
     `closed_prs_checked`, `findings`, `restored`, `tool`.
   - The next audit starts from the last row's `last_checked_commit` (`git log <last_checked_commit>..<ref>`),
     so nothing is checked twice and nothing is skipped.
9. **Licence: Apache.**
   - New and absorbed code is Apache-2.0, or a licence compatible with Apache-2.0 (MIT, BSD, ISC, 0BSD, public
     domain). A repository whose own licence is different (EPL-2.0, for example) keeps that licence for its files.
   - Code under an incompatible licence is never copied, close-translated, or relicensed; an LLM rewrite
     does not remove source rights. Independently derive original code from lawfully accessible specifications,
     research, and abstract algorithmic ideas, document design provenance, and review patents and other rights.
   - Copyright protects the expression, not the algorithm. A patent can still cover an algorithm, so check the
     source for patents before shipping its algorithm.


## Mandatory source, rights and promotion gates (2026-11-18)

10. **No false no-loss proof.** Before a source-changing PR, inspect the exact current
    default/develop HEAD, all reachable relevant branch histories and merge parents, open
    and closed PR patches, deletion/revert commits, tests and release baselines.
    Classify historically found capabilities as ACTIVE, SUPERSEDED_WITH_PROOF,
    PENDING_INTEGRATION, INTENTIONALLY_WITHDRAWN or UNRESOLVED_GAP. Source SHA-256 and
    per-atom structure/logic hashes establish identity; SimHash finds candidates, never
    proves equivalence. Preserve a source-loss ledger with precise paths, commits and
    provenance. A missing audit or unresolved loss is HOLD, not a merge approval.

11. **Reproducible atomization, patternization and validation.** In Java/JNI-family
    tasks, reuse or first create a canonical Maven/OpenRewrite recipe in private
    `hsoliwal/com.synexia`. Freeze the source and behavioral contract; inventory
    AST/atoms, mask strings and comments before heuristic regex, gather control-flow,
    effects and API/ABI evidence, then select patterns and execute mechanically
    per file/atom with source/postimage SHA-256. Run adversarial fixtures, compiler,
    JUnit, native/JNI/ABI, runtime and performance checks as applicable; verify
    fixed-point replay. Public product forks receive the qualified executed outputs,
    tests and data-only recipe/provenance references, not private recipe sources.
    For non-Java projects, use their established equivalent deterministic proof path.

12. **Rights before reusable code.** Original new code is Apache-2.0 whenever the
    author has the rights and the target's inherited license obligations permit.
    Inspect each donor's LICENSE, NOTICE, SPDX, copyright owners, modification and
    patent terms before accepting code or dependencies. Prefer verified Apache-2.0
    and individually reviewed Apache-compatible permissive sources; never assume
    that a compatible dependency's original license changes to Apache-2.0.
    Existing GPL/Classpath, EPL and other upstream-licensed files retain their
    obligations and notices. Do not falsely label a mixed-license fork as
    Apache-2.0-only. That is a separate, independently qualified distribution goal.

13. **Algorithm ≠ protected expression.** Abstract methods and mathematical ideas
    are not the same as copyright-protected code expression under US copyright law.
    Derive a distinct algorithm implementation from public specs, papers, documented
    behaviors and independently generated tests. Avoid copying or near-translating
    protected source, comments, documentation or test expressions. An LLM
    atomizer/patternizer is an analytical aid, **not** a copyright-laundering tool.
    Check patent, confidentiality, contractual and applicable jurisdiction concerns;
    if permissions are uncertain, HOLD. Retain reference, license and design provenance.

14. **History-preserving, test-gated integration.** Do not rebase or force-push
    `develop`, discard conflicting PRs, flatten merge ancestry or overwrite newer
    implementations. Reconcile API clashes with tested adapters/overloads and
    validate both baseline and extension consumers. Qualify a merge against both
    parents, then make a regular merge commit; record source/head SHAs, retained
    capabilities, rejected alternatives, benchmark/CI results, unresolved platform
    gates and final readback tree. Keep unqualified PRs OPEN/DRAFT/HOLD, never
    merge by assertion. These written rules do not themselves constitute an
    executed full-history audit or release certification.

Legal reference documents:
- https://www.apache.org/licenses/LICENSE-2.0.html
- https://www.copyright.gov/circs/circ31.pdf
