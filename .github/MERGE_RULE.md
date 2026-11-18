# Merge rule: superset, best of best, additive API

This rule applies to every pull request in this repository and to every merge between branches. It governs
conflict resolution. A PR can remain open or draft until its mandatory evidence and tests pass;
conflicts alone never justify discarding a compatible contribution.

1. **Superset.** Preserve every valid supported capability of both sides, including behavior, tests, documentation and data.
   Preserve previously proven improvements; explicitly classify deliberate removals and security corrections.
2. **Best of best.** Choose by correctness, then predictability, then measured performance, then elegance.
   Validate both sides' contracts and tests on the delivered tree; record representative and adversarial workload results and tradeoffs.
   Preserve distinct behavior in the existing owner; keep separate strategies only where their supported contracts or measured workloads justify them.
3. **Additive API.** Public API is only ever added:
   - Both sides' public types, members and options stay.
   - If two signatures clash (same name and erasure, or the same option with different meanings), keep the
     one already on the base branch and add the other under a distinct name, an overload, a default method
     or an adapter.
   - Deprecate rather than delete. Never change an existing public signature incompatibly.
4. **Data, ledgers and indexes.** Preserve all distinct valid records, reconciling duplicates or collisions with provenance; do not silently discard data. Regenerate generated files
   (indexes, checksums, provenance) with their generator instead of merging them by hand.
5. **Already contained.** Verify actual source and contract parity when work appears to be present in another form.
   When integration is authorized and its gates pass, preserve ancestry with a regular merge commit and retain every compatible addition.
   An ancestry-only merge is not delivered functionality; unresolved parity remains explicitly pending.
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
   Traverse every reachable root and merge parent across all branch/tag snapshots and all PR states.
   Append an honest UTC checkpoint to `.github/HISTORY_AUDIT.tsv`, preserving its nine-column schema and earlier rows.
   Record explicit coverage, observed heads, per-ref progress, checked PR identities, roots, gaps and unprocessed frontiers.
   Refresh refs and changed older PRs on every run; a single `git log old..new` range cannot establish repository-wide coverage.
   A policy-only check leaves `last_checked_commit` empty and does not advance historical audit coverage.
9. **License: prefer verified Apache-2.0.**
   Prefer Apache-2.0 donors or independently authored Apache-2.0 code where the destination permits; preserve each reused component's actual license and notices.
   Check exact license versions and integration compatibility, including inherited OpenJDK/GPL and EPL obligations; never relicense another owner's implementation by assertion.
   Independently implement abstract algorithms from appropriate specifications and references when source reuse is incompatible.
   Atomization, patternization, translation or LLM rewriting does not establish independence or remove license obligations.
   Record provenance and consider patent, contractual and other applicable rights before delivery.

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

## Full-history coverage and independent implementation (2026-11-18)

This clarification governs any older wording above that could imply automatic merging, a single-commit audit cursor, or automatic relicensing after a rewrite.
It applies before and after every integration and before modifying an existing implementation.
Installing this policy is not evidence that its audits have run.

### Preserve the supported superset and select by evidence

Preserve all compatible, supported capabilities from upstream, the current tree, historical implementations, branches and PRs.
Compare candidates in this order: **correctness > predictability > performance > elegance**.
Retain both baseline and extension tests, previously proven optimizations and resource properties.
Select one existing implementation owner when it can preserve the complete contract; retain genuinely distinct strategies when evidence justifies them.
HEAD, a newer commit, a benchmark win, a merge label and a low diff count do not establish that an implementation is better or that no capability was lost.

Extend public APIs additively while retaining source, binary, behavior, platform, serialization and applicable JNI/ABI contracts.
Test overload resolution, erasure, default-method conflicts, dispatch, callbacks, exceptions, ownership and lifecycle.
Do not blindly choose ours/theirs or union conflicting data rows.
Preserve distinct valid records and reconcile duplicate identities with provenance.
Deliberate security fixes and documented removals require explicit disposition; never resurrect vulnerable or invalid behavior simply because it existed.

### Audit all reachable history, then resume without gaps

The initial audit traverses the complete parent DAG back to **every reachable root**, including side branches and every parent of every merge.
Enumerate and pin all accessible branch and tag refs and all open, draft, closed and merged PRs, with head/base/merge SHAs and pagination coverage.
Check patches and actual delivered source, follow renames/moves, and compare each merge against its parents and merge base.
Look for overwritten fixes, dropped tests, reverted improvements, truncated trees, lost files and work whose ancestry survived while its source did not.
Inspect earlier good implementations as candidates for the current owner.

Classify each affected capability as SURVIVES, SUPERSEDED_PROVEN, LOST, OVERWRITTEN_UNPROVEN, REVERTED, RESTORED, REPLAYED or PROOF_REQUIRED.
Record the introducing/removing commits, PRs, exact paths, contract mapping and verification.
Use source, structure and logic hashes plus SimHash to find candidates; these signals do not prove semantic equivalence.
Restore proven losses through an append-only corrective commit and regression coverage.
Never rebase develop, force-push shared history, prune audit evidence or bypass required merge checks.

On every later audit, refresh the complete ref/PR inventory and reconcile it with the previous snapshot.
Track each ref's old/new SHA, visited commit identities, unprocessed parent frontier, discovered roots, PR identities and changed PR metadata.
Revisit changed, newly discovered and non-fast-forward refs and older PRs that changed.
`git log old..new` is useful only for one verified ancestry segment; a single timestamp or the last row's SHA cannot establish global coverage.
Record inaccessible/deleted refs, unavailable objects, API limits and other gaps; never represent unavailable history as reviewed.

### Record what was actually checked

Append to the established `.github/HISTORY_AUDIT.tsv`; preserve earlier rows.
Keep its nine columns: `checked_at`, `ref`, `last_checked_commit`, `merges_audited`, `branches_checked`, `closed_prs_checked`, `findings`, `restored`, `tool`.
`checked_at` is an actual UTC check time, not a promised completion date.
Put structured coverage metadata and the detailed checkpoint reference in `findings`; do not put tabs or newlines inside a cell.

Use explicit states NOT_STARTED, POLICY_INVENTORY_ONLY, PARTIAL, COMPLETE_FOR_SNAPSHOT or BLOCKED.
A policy/instruction inventory records the observed head separately and leaves `last_checked_commit` empty; it does not advance an audited-history cursor.
For a partial audit, record only the segment actually checked and retain every remaining frontier.
Set a full-audit timestamp only after roots, refs, PR pages, source-loss findings and required checks are accounted for at the recorded snapshot.
Keep policy installation, last attempt, last completed segment and last complete snapshot distinct.

Detailed private recipe/evidence records remain in canonical `hsoliwal/com.synexia`.
Public product repositories keep this policy and a compact checkpoint/reference, without receiving private recipe implementations or evidence bundles.
Existing source-changing tasks still use the canonical deterministic Maven/OpenRewrite recipe, serial per-file passes, fixed-point/refusal checks, and applicable diff -> lint -> compile -> tests -> runtime gates.
Missing proof remains explicit.

### Prefer Apache with verified provenance

Prefer exact **Apache-2.0** grants and Apache-licensed alternatives.
Use Apache-2.0 for independently owned new material where the destination's obligations permit.
Check the actual license version, exceptions, copyright, NOTICE, modification and patent terms per donor and destination; an "Apache variant" or compatibility label does not relicense copied code.
Preserve all existing third-party/OpenJDK/EPL/GPL rights and notices.
In particular, Apache-2.0 and GPLv2 source cannot simply be combined or relabeled on the assumption that both are open source.
An Apache-only distribution is a separately verified destination, not a description of a mixed licensed fork.

Algorithmic ideas and functional methods are distinct from protected implementation expression.
Use lawfully accessible specifications, papers, behavior descriptions and independently authored tests to derive an original implementation.
An LLM may help analyze, atomize and patternize those ideas, but copying, close translation, renaming or refactoring does not establish independence or remove source license obligations.
Record references, design decisions, authorship and rights review; never label a source-derived rewrite as independent merely because an LLM produced it.
Patent, confidentiality, contractual and applicable jurisdiction constraints must also be considered.
Uncertain rights remain unresolved until established.

Primary references:

- https://www.apache.org/licenses/LICENSE-2.0.html
- https://www.apache.org/licenses/GPL-compatibility.html
- https://www.copyright.gov/register/tx-programs.html
- https://www.copyright.gov/title17/92chap1.html
- https://git-scm.com/docs/git-rev-list

<!-- MERGE-SUPERSET-HISTORY-20261118-2 -->
