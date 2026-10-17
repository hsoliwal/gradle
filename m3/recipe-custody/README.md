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
