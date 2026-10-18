<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
     SPDX-License-Identifier: Apache-2.0 -->
# Native compiler recipe custody

This optional source directory carries the exact canonical Synexia snapshot owners, pinned Maven/OpenRewrite SDK harness, recipe tests, thin receiver, and the `gradle-fork` source dataset used for this fork's compiler integration. `OWNER_CUSTODY.json` records the canonical owner identities and this dataset's qualified source/result hashes. Synexia remains the transformation source authority. The native build tool has no runtime dependency on this proof harness.

## Execute the recipe and receiving contracts

Use Java 21, Maven 3.9 or newer, and Python 3. From this directory:

```sh
mvn -B -ntp -Dm3.dataset=gradle-fork \
  -Drecipe.owner.root="$PWD/sdk-owner" -Pcontract verify
```

The POM pins OpenRewrite 8.17.1 and JUnit 5.10.2. Add `-o` when dependencies are already cached. A retained rerun needs a fresh `-Dm3.contract.output=/absolute/new/proof` directory. Clean the selected dataset's Maven output after changing membership so unselected old resources cannot remain on the classpath.

The command runs five actual SDK tests, eight input-sealing tests, and nine isolated receiver cases. It covers exact replay, fixed point, reversed input order, mixed pre/postimages, absent inputs, source and result tampering, per-target drift, post-scan changes, symlink refusal, and modes/timestamps. Fresh `target/gradle-fork/candidate` files are the actual SDK Results, with complete source bindings and an exact result manifest.

## Apply into an isolated fork checkout

Set `M3_FORK_TARGET` to an isolated checkout of this fork, then run:

```sh
python3 receive.py --dataset gradle-fork --target "$M3_FORK_TARGET" \
  --recipe-owner "$PWD/sdk-owner" --check
python3 receive.py --dataset gradle-fork --target "$M3_FORK_TARGET" \
  --recipe-owner "$PWD/sdk-owner"
```

All current targets must match their pinned preimages or postimages before any write. Repeat application at the fixed point writes nothing. The receiver makes serial atomic per-file replacements; it does not claim a multi-file filesystem transaction under concurrent writers or interrupted I/O.

This mirror contains only the native product dataset. The separate canonical custody dataset transfers this directory after source qualification and is not embedded recursively. Keep the original source notices and source hashes intact. `LICENSE.txt` applies to the first-party Synexia owners and harness; native fork snapshots retain their original notices, and third-party Maven dependencies retain their own licenses.

The receipts here qualify recipe source replay. Native build-tool tests, distribution builds, and license checks remain separate receiving-project gates. Run the receiving repository's documented build and test commands to verify the actual product.

## Current canonical recipe source custody (2026-10-18)

The reusable recipe source closure from this fork is held in `hsoliwal/com.synexia` at `215546d1ca0e724eb2c8e2e8ef6b4adce724733a`. The [dated custody binding](../../.m3/SYNEXIA_RECIPE_SOURCE_CUSTODY_20261018.json) records the exact public source revisions, canonical snapshot paths, source seals and the separate execution limits. The current source-custody ledger is `synexia-openrewrite-recipes/recipe-crates/public-fork-home-20261018/SOURCE_CUSTODY.tsv` (SHA-256 `ebcfbd42a49eec806f0a4572af731828deda41f22c17250d34d49af985d78f1d`).

Existing public source and SDK/proof mirrors retain their recorded provider revisions because their build and proof callers have not yet migrated to a qualified canonical handoff. Keep improvements in the canonical recipe source and return qualified postimages to this target. Existing native builds, product APIs, runtime acceptance and promotion remain target-owned.

The retained-mirror source revision is the frozen custody origin. Separate current packet applications may update target paths under those roots; their exact inputs, outputs and qualification limits are recorded independently below.

The binding's original-packet verification covers only its listed packets. The [current recipe census](https://github.com/hsoliwal/com.synexia/blob/215546d1ca0e724eb2c8e2e8ef6b4adce724733a/synexia-openrewrite-recipes/recipe-crates/public-fork-home-20261018/evidence/CURRENT_RECIPE_CENSUS.json) (SHA-256 `cfa9edb42a99cb2e54a988b28de7078a3233fdc667c5e4d8f979675e0f767ee0`) records the separate admission, application and gate evidence for current packets. Canonical kernel checks do not establish SDK or product runtime qualification: the canonical SDK attempt was blocked before compilation with zero tests. The binding-only application does not run native product qualification; gate attempts for separate product packet applications are recorded in the current census.

The sealed binding and execution-receipt packets under `.m3/recipe-source-custody-20261018/` are pinned proof mirrors. They grant no competing recipe ownership or product promotion authority.
