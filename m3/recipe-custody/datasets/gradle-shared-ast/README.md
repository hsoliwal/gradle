<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
     SPDX-License-Identifier: Apache-2.0 -->
# Shared compiler task dataset

This additive dataset records eleven exact receiving sources against Gradle fork
commit `47f82a74e0c0e49e9d4aad73949405db22e775fc`: ten Java sources and the
compiler integration document. It preserves the fork's existing native
`m3AstEnabled` property and `-Xplugin:M3AST` selection. The optional JSR199 provider
hook is a separately selected path that admits explicit local provider assets and
retains the original compiler task, diagnostics and incremental collectors.

The source transformation uses this directory's existing canonical Synexia
snapshot owners. `ENGINE_EFFECTIVE.tsv` binds the exact compiled owner bytes;
the original owner identities and notices remain in the parent custody files.
The `gradle-fork` dataset and its original receipts are unchanged.

## Replay and receive

From `m3/recipe-custody`, use Java 21 and Maven 3.9 or newer:

```sh
mvn -B -ntp -Dm3.dataset=gradle-shared-ast \
  -Drecipe.owner.root="$PWD/sdk-owner" -Pcontract verify
```

This runs the existing five SDK tests, eight input-sealing tests and nine
receiver cases against this dataset. For a repeated retained run, supply a fresh
`-Dm3.contract.output=/absolute/new/proof` path. Dependencies may be replayed
offline after they have been cached. The POM, engines, tests and receiver remain
the existing source owners.

After successful qualification, set `M3_FORK_TARGET` to an isolated receiving
checkout and run:

```sh
python3 receive.py --dataset gradle-shared-ast --target "$M3_FORK_TARGET" \
  --recipe-owner "$PWD/sdk-owner" --check
python3 receive.py --dataset gradle-shared-ast --target "$M3_FORK_TARGET" \
  --recipe-owner "$PWD/sdk-owner"
```

Every target must match its exact preimage or postimage before any write. The
receiver uses qualified SDK Results and serial atomic per-file replacement.
Fixed-point application writes nothing. It does not provide a multi-file
transaction under concurrent writers or interrupted I/O.

## Product qualification

Recipe replay verifies source custody. The fork's original compiler subproject
tests and full distribution task qualify the receiving product separately:

```sh
./gradlew :java-compiler-worker:quickTest :java-compiler-plugin:quickTest \
  -PtestJavaVersion=21 :distributions-full:binDistributionZip
```

The optional M3JDK receiver provider and its JavaCompile consumer fixture live at
`m3/tooling/module-packs/build-support/gradle-provider` in M3JDK. That fixture
compares ordinary compiler behavior, original task and tree identities, explicit
lazy indexing, cold replay, annotation processing, class bytes, incremental
dependents, task inputs, modules and diagnostics. Normal compilation delegates
the original `task.call()`. Full-analysis Rewrite conversion requires a separately
selected and qualified phased contract. This dataset alone does not establish a
performance improvement or whole-Gradle compatibility.
