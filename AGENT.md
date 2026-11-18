<!-- SPDX-License-Identifier: Apache-2.0 -->
# MOP SDK — Repository Agent Contract

**Policy revision:** MOP-AGENT-2026-11-18-1
**Scope:** this repository and its subprojects; subordinate project-specific instructions and stronger existing `AGENTS.md`/security/rights constraints remain controlling.
**Disposition:** documented adoption proposal. Installing this document does **not** certify source atomization, historical coverage, license clearance, a successful build, or any verification not actually run.
**Canonical reusable transformation owner:** `hsoliwal/com.synexia`; JDK/SWT/Nebula and other product forks are qualified postimage receivers, not extra recipe or evidence stores.

## 1. Machine-Oriented Programming (MOP)

- **Decompose downward:** repository -> project -> module -> package -> file -> type/interface/field/constructor/method/native symbol -> cohesive semantic atom. Inventory source, exact revision, interfaces, signatures, documentation, dependencies, callers, visibility, effects, runtime contracts and provenance before proposing a change.
- **Recompose upward:** proven semantic atom -> file -> package/interface and callers -> module -> project -> repository -> system/SDK. Interfaces and dynamic/native edges are cross-cutting proof obligations, not necessarily containment levels.
- **Atomize -> Patternize -> Annotate -> Document.** A file is a bounded preparation unit; it is not automatically behaviorally isolated. Prefer meaningful private/static/instance atoms and documented composition methods when safe, never forced micro-methods or fake purity. Explicitly record `NO_SAFE_EXTRACTION`, `UNVERIFIED`, or typed residue.
- Make contracts and patterns addressable machine memory: per-atom purpose, signatures, inputs/outputs, pre/postconditions, ownership, effects, state transitions, exception and evaluation order, concurrency, serialization, JNI/native/GC lifecycles, algorithm and pattern IDs, implementation/data-structure alternatives, source hashes, dependencies, tests, and verified evidence.
- **Parallelize candidate preparation; serialize mutation admission.** Pin input and dependency closure, partition deterministically, bound workers, allow one writer per file, compare candidate diffs, verify leaves and only then propagate proofs upward. A changed effect/interface reopens downward impact discovery. Repeat bounded passes to a recorded idempotent fixed point; no hidden stages or replay.
- Hashes (SHA-256, LogicHash, StructuralHash, SimHash) support custody, lookup and candidate clustering, **not** automatic semantic equivalence. LLM explanations, pattern labels, benchmarks without baselines, and finite tests cannot prove global optimality. Select the best **demonstrated** implementation for explicit correctness, compatibility and workload constraints.

## 2. Additive best-of-best merge: no code loss

- Treat accepted upstream APIs/ABI/behavior **and** accepted fork-specific extensions, tests, fixes, optimizations, memory/resource and native contracts as a protected subset. Build a verified, backward-compatible **superset**; never replace it with a narrower upstream or latest-branch snapshot.
- Before approving a merge, enumerate and paginate available branch heads, tags, ancestry to earliest reachable roots, merge parents, PRs of all states, PR head/merge references, reverts, renames/deletions and source blobs. Check diffs, source symbols, callers, released APIs and tests for bad commits, silent losses, or unproven supersession. An ancestry link or merged PR is **not** proof of surviving functionality.
- Compare competing compatible implementations against independent differential tests, source/binary/reflection/overload/serialization compatibility, safety, measured CPU/heap/native cost, maintainability and provenance. Restore losses additively through the owning validated recipe, adapters and tests, never by blind cherry-pick or history rewrite.
- Record exact `repository`, `ref`, `head_sha`, `earliest_reachable_sha` if established, `inventory_observed_at_utc`, `history_last_attempted_at_utc`, `history_last_verified_at_utc`, scanned/total branch-tag-PR counts, gaps, candidate loss/regression classifications, checkpoint, evidence URL, and verification gates in the canonical Synexia history ledger. **NOT_RUN/PARTIAL are not VERIFIED**; a new HEAD invalidates unrefreshed coverage. Never fabricate a last-checked date or assert inaccessible history was inspected.

## 3. Rights, Apache preference and independent algorithms

- Prefer **Apache-2.0** for newly authored first-party **separable, eligible** files when all rights holders authorize it. Preserve existing upstream licenses, NOTICE, attribution, SPDX, modifications, patent terms and third-party provenance. Apache-like variants require explicit compatibility review. Do not silently relicense GPLv2/OpenJDK, EPL, other copyleft, proprietary or unknown source.
- Mathematical algorithms, abstract ideas and methods are generally not protected by copyright as such; source expression, code organization where sufficiently original, patents, confidential material, licenses and contractual obligations still matter. An LLM paraphrase, atomization, transpilation or pattern extraction **does not erase protected expression or donor obligations**.
- Prefer already eligible FOSS/library/ROP atoms and existing owners; otherwise implement independently from lawful functional specifications and independent tests, with provenance and patent review when applicable. Competitive programming sites are algorithm-study material, not assumed license-free code donors. Never copy incompatible implementations merely because the algorithm is abstract.

## 4. Mandatory agent execution and acceptance gate

- Read the repository's existing `AGENTS.md`, `CLAUDE.md` pointers, merge policy, licenses, local rules, locked M3Scale canon and actual code before action. Documentation precedes code; approved namespaces, scope, task packet and public/API/behavior locks remain binding. No silent move/delete/rename, excluded-path edit, or unapproved API drift.
- Source-altering recipes, templates, semantic catalogs and qualification memory remain canonical in Synexia; target products receive only legal, verified postimages and relevant product-native tests. Use the existing recipe instead of an ad-hoc generative rewrite.
- Required proof sequence: **inventory + history and provenance -> task packet/contract -> proposal/diff -> lint/static checks -> compile -> tests -> runtime/native checks when applicable -> replay/fixed point -> independent review -> serial admission**. Never invert verification order, self-certify model output, claim tests not run, or force-promote unknown semantics.
- Capture before/after content identity, changed-file inventory, exact diff, verification commands/logs, receipts, unresolved obligations and honest `PASS/FAIL/BLOCKED/NOT_RUN` dispositions. Preserve output contract and session/event/TODO history; do not place private proof or recipe stores into clean public targets.

**Related authority:** the existing repository `AGENTS.md`; [M3JDK merge governance](https://github.com/hsoliwal/M3jdk21/blob/master/.github/MERGE_GOVERNANCE.md); Synexia's locked M3Scale and MOP SDK invariants (see [MOP SDK policy proposal](https://github.com/hsoliwal/com.synexia/pull/18788)). This document adds policy; actual code correctness still requires repository-specific proof.

## 5. Known algorithm / pattern registry and unknown residue — mandatory
<!-- MOP-KNOWN-ALGORITHM-PATTERN-20261118-1 -->

Before composing an atom, classify its algorithm and architectural pattern against the source-pinned, previously admitted Synexia catalogue. A **known** binding includes a stable algorithm/pattern ID and revision, role/participants, preconditions, effects, exception/ordering/concurrency contract, license/provenance and specifically executed proof receipts. An identifier or semantic hash is **not** a proof of equivalence. If no admitted algorithm or pattern exists, tag the implementation or proposal as `UNKNOWN_ALGORITHM` / `UNKNOWN_PATTERN` with a machine-readable residue ID, source pin, exact reason, dependency/effect obligations and owner; preserve the existing implementation. Use an existing approved source annotation if safe or a canonical metadata/documentation tag, never invent a compile-breaking or reflection-changing annotation. Unknown structure may become an admitted pattern only after independent recognizer/template/contract/oracle/fixture verification and serial review.

Interfaces and signatures are the semantic skeleton; inline Javadoc and source-bound catalogue entries are persistent machine memory. Agents must retrieve this memory before changing code. Document each meaningful file atom and its composition graph, update just the affected dependency closure, revalidate bottom-up, and demonstrate bounded deterministic idempotence; no “best possible code” claim follows from atomization alone.

**Canonical additive specification:** [SYNEXIA-MOP-SEMANTIC-ATOM-ALGEBRA-1](https://github.com/hsoliwal/com.synexia/blob/policy/mop-agent-history-20261118/docs/M3-SCALE/invariants/SYNEXIA-MOP-SEMANTIC-ATOM-ALGEBRA-1.md). This is an implementation obligation, **not** evidence of completed repository-wide atomization, exhaustive history review, or a qualified runtime build.
