<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
     SPDX-License-Identifier: Apache-2.0 -->
# Runtime verification recipe addendum

This directory extends the existing native compiler integration custody without changing its owners, Maven harness, tests, receiver, or native source snapshots. `RUNTIME_CUSTODY.json` pins each new dataset, canonical source copy, and prior owner dependency. The runtime receipts retain the precise source build and VM scope of their original executions.

Use Java 21, Maven 3.9 or newer, and Python 3. From this directory, replay the additional datasets:

```sh
mvn -B -ntp -Dm3.dataset=gradle-runtime-evidence -Drecipe.owner.root="$PWD/sdk-owner" -Pcontract verify
```

Add `-o` when all pinned Maven dependencies are cached, and use a fresh `-Dm3.contract.output=/absolute/new/proof` to retain another proof. Each command runs five real OpenRewrite SDK tests, eight source sealing tests, and nine isolated receiving cases. Fresh SDK ledgers and Results are generated under `target/<dataset>/candidate`; the copied qualification JSON describes the original execution. The ordinary Apache license comments on authored recipe metadata are included in the exact SDK input binding.

After a successful replay, inspect the receiving decision with `python3 receive.py --dataset <dataset> --target <isolated-checkout> --recipe-owner "$PWD/sdk-owner" --check`, then apply by omitting `--check`. Complete preimage/postimage, source binding, engine pin, mode, path, and actual SDK Result checks are mandatory. A repeat writes no files. The custody transfer dataset is not embedded into itself.

The runnable verifier's own help and the native integration README describe runtime options. Runtime success, source reactor coverage, and acceptance of a complete M3 VM are separate claims; use the corresponding evidence records for each claim. A passing recipe replay alone does not establish native runtime behavior.
