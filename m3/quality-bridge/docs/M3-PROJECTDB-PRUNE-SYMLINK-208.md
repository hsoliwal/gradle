<!-- SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Maven #205/#208 module discovery: Gradle cross-fork qualification

Original source: [Maven #205](https://github.com/hsoliwal/maven/pull/205)
prunes irrelevant `build`, `target`, `.gradle`, `node_modules`, and source/VCS
directories **before** enumerating their descendants. The change initially
lost legacy module discovery for a symlinked Gradle descriptor file.
[Maven #208](https://github.com/hsoliwal/maven/pull/208) restores the previous
descriptor-file check while keeping early subtree pruning.

This Gradle bridge uses exact **merged Maven #208** source
`fc8e5eee6c17eb2b8553204795a32f020078c9db`, not a floating branch.
The independent JDK 17/21 CI lane runs Maven's original
`ModulesPruneContractMain` legacy oracle and native Gradle JUnit parser
before building the original polyglot and project-DB JARs.

`M3ProjectDbPrunedSymlinkRealEngineTest` then configures the existing
`m3ProjectDbIngest`/`m3ProjectDbQuery` Gradle tasks with separately
SHA-256-pinned original Maven JARs. The fixture has an ordinary Gradle root
and a child module whose `build.gradle` is a symlink to a regular descriptor.
Only that child's actual Gradle JUnit failure may reach the original Maven
M3IndexDB with module/source-line provenance. Reports under `node_modules`
and `target` must not be inventoried; repeating ingestion must reuse the
unchanged source content. Platforms that prohibit creating symlinks skip
only this filesystem-specific TestKit case; Maven's original oracle still runs.

There is **no duplicate Gradle module scanner**, parser, database, cache or
new task API. The existing opt-in
[OpenRewrite consumer recipe](../recipes/enable-m3-quality-bridge.yml)
remains the recipe-first registration boundary: it does not run ingestion or
query during plugin application, nor authorize changing Maven descriptors.

Local source proof: original merged #208 Java17-compatible contract closure
ran from the checked-out source with the reduced JDK21 compiler fallback:
`PASS: native Gradle modules and JUnit case evidence via original Maven parsers`;
`PASS: 4 bounded Gradle/Maven module-pruning oracle cases`.
This is not a full JDK17 `--release` proof in the reduced local environment.

**Pending release gates:** hosted Gradle TestKit against both original JARs;
JDK17/21 matrix logs, Windows/macOS symlink behavior, large-workspace
performance measurements, and an OpenRewrite SDK recipe replay. The source
optimization does not license skipping compilation or reusing an unverified
Gradle or Maven task-cache entry.
