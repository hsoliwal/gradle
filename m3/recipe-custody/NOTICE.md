<!-- SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
     SPDX-License-Identifier: Apache-2.0 -->
# Source custody and licenses

This task's new qualification scripts, metadata, tests, and documentation are Apache-2.0.

The Java/text snapshot recipes and source-scope helpers are existing first-party Synexia code under Apache-2.0. `ENGINE_SOURCES.tsv` identifies their exact origin bytes and GitHub links at `e244d68fdee88d4a406be48e4ff1d31b60a17988`. The test/receiver structure reuses the previously qualified `m3-deep-filter-list-20261017` first-party crate. Task-specific edits remove collection assumptions and add repository datasets; no collection implementation is imported.

OpenRewrite and JUnit are resolved through their pinned Maven coordinates. Their libraries retain their own licenses and notices; this crate does not relicense them. Receiving preimages and generated product payloads retain the notices and licenses of their respective repository owners. A source identity or private repository location does not itself establish permission to relicense third-party code.
