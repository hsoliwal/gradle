# Merge rule: superset, best of best, additive API

This rule applies to every pull request in this repository and to every merge between branches. It decides
how conflicts are resolved. A pull request is never left open, and never closed, only because it conflicts.

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
4. **Data, ledgers and indexes.** Take the union of rows, with no lost lines. Regenerate generated files
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
