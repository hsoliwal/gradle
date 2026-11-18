<!-- SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native build review and recipe suggestions

The optional `io.synexia.m3.quality` plugin exposes the original Maven
smart-builder's standalone Java entry points through Gradle tasks. This
continues the shared Maven/Gradle implementation: Maven owns the parsers,
receipt admission, model-provider integration and report schemas; Gradle
owns task configuration and process execution.

## Tasks

| Task | Original Java owner | Report under the configured build directory |
| --- | --- | --- |
| `m3PreBuildAdvice` | `io.synexia.maven.smart.BuildHookMain`, `pre-build` | `m3-smart/pre-build.json` |
| `m3PostBuildAdvice` | `io.synexia.maven.smart.BuildHookMain`, `post-build` | `m3-smart/post-build.json` |
| `m3CodeAgent` | `io.synexia.maven.smart.CodeAgentMain` | `m3-smart/code-agent.json` |

All three tasks are explicitly invoked and untracked. Applying the plugin or
listing tasks does not execute them. The native `build`, `check`, compiler,
dependency graph and output-cache decisions retain their existing behavior.

The task implementation targets Java 17. The selected Maven producer requires
Java 21; choose its executable explicitly when Gradle itself runs on Java 17.
CI pins the original Maven source at
`ff1b0939e8ac962352d5fe4f4461808e867ef670` and builds its
`tools/m3-smart-builder` module with its own tests. The jar must be built or
installed separately and supplied with its actual SHA-256.

## Configure the original producer

For a Groovy build script in a project using the existing bridge:

```groovy
plugins { id 'io.synexia.m3.quality' }

tasks.withType(io.synexia.gradle.quality.M3SmartReviewTask).configureEach {
    engineJar.set(layout.projectDirectory.file('tools/m3-smart-maven-plugin-0.1.0-SNAPSHOT.jar'))
    engineSha256.set(providers.gradleProperty('m3.smart.sha256'))
    javaExecutable.set(providers.gradleProperty('m3.smart.java'))
}

tasks.named('m3PreBuildAdvice') {
    sourceRoot.set(layout.projectDirectory.dir('src/main/java'))
}
```

Invoke `m3PreBuildAdvice` with `-Pm3.smart.sha256=<actual-jar-sha256>` and
`-Pm3.smart.java=<java21-executable>`. The default backend is `none`: the
original producer records available project facts without loading a model.
Gradle-only projects may have no POM; that fact remains `unavailable` in the
producer's report. Source facts can still be supplied explicitly.

Provider dependencies use the same existing `providerJars` and
`providerJarSha256` contract as the other bridge tasks. Every supplied JAR
must have a matching pin. Argument vectors are passed directly to Java.

## Preserve an observed build result

`m3PostBuildAdvice` requires `buildOutcome` to be exactly `success` or
`failure`. It can be set in the task or supplied as
`-Pm3.smart.buildOutcome=failure`. A caller may configure `buildLog` to an
existing project-local log. A successful advisory task means that the
advisory process completed; the report retains the explicitly supplied
build outcome. The bridge does not infer the outcome from task order.

```groovy
tasks.named('m3PostBuildAdvice') {
    buildLog.set(layout.projectDirectory.file('build.log'))
    sourceRoot.set(layout.projectDirectory.dir('src/main/java'))
}
```

The original owner also supports its optional CPU summarizer. Set
`summarizer` to the explicitly selected command and, optionally,
`summarizerTimeoutSeconds` in `1..600`. Maven retains its literal command
tokenization, bounded result handling and failure reporting. No summarizer
is selected by default.

## Source-bound recipe suggestions

`m3CodeAgent` requires the canonical preparation report, atom/pattern
manifest, and registered recipe catalogue for the selected source root:

```groovy
tasks.named('m3CodeAgent') {
    sourceRoot.set(layout.projectDirectory.dir('src/main/java'))
    preparationReport.set(layout.projectDirectory.file('receipts/preparation.json'))
    atomPatternManifest.set(layout.projectDirectory.file('receipts/atoms.tsv'))
    recipeCatalog.set(layout.projectDirectory.file('receipts/recipes.tsv'))
    // Optional original Maven machine-fingerprint evidence:
    // machineEvidence.set(layout.projectDirectory.file('receipts/machine-evidence.tsv'))
    // requireMachineEvidence.set(true)
}
```

Generate those receipts through their existing canonical owners. Maven
validates their schemas and current source bindings before nomination, then
rechecks the bindings before publishing its result. A supplied `candidateFile`
can exercise the owner's nomination validation with `backend=none`; it is
exclusive with a model backend. The existing Maven owner limits nominations
to catalogue entries and leaves recipe execution and verification to the
canonical recipe workflow.

Build-advice tasks expose the corresponding optional admission controls:
`requireAtomPattern`, `atomPatternManifest`, `preparationRequired`,
`preparationReport`, and `sourceRoot`. Supplying a preparation report requires
`preparationRequired=true`. These flags preserve the original producer's
explicit admission modes.

## Optional native model commentary

Native commentary requires both `optionLlm=true` and an explicitly selected
`backend` of `cli` or `jni`. The project properties are `-Poptionllm` (or
`-Poptionllm=true`) and `-Pm3.smart.backend=cli|jni`. An absent flag or
`-Poptionllm=false` leaves model invocation disabled; other boolean spellings
are refused.

For the selected native mode, configure:

| Property | Required for |
| --- | --- |
| `chromeLlmJar` and `chromeLlmJarSha256` | Both native modes; original separately installed provider JAR |
| `modelFile` | Both native modes; explicitly selected local model |
| `llamaExecutable` | `cli` |
| `jniLibrary` | `jni` |
| `timeoutSeconds` | Optional native inference timeout, `1..600`; default `120` |

These are native Java/JNI paths. The bridge does not download models, install
providers, or restore removed browser-managed AI hooks. The flags permit the
explicit task's native provider call; they do not approve recipes or edits.

## Gradle configuration-cache contract

Directory and option inputs are managed Gradle properties. Advice and
recipe-nomination tasks do not access `Project` during execution. Existing
Eclipse-model, provider-inventory and project-database tasks now capture
their directories from injected `ProjectLayout` as well.
Changing a provider-backed build directory after a task is realized remains
supported for both plugin-created and directly registered tasks.

Configuration-cache reuse restores configuration. Each untracked task still
executes its current pin, path and producer validation. In particular:

- Replacing an engine or provider JAR is checked again on replay.
- A project-database query checks that its ingested store still exists.
- Code-agent source changes invalidate the earlier receipt bindings.
- Custom build-directory boundaries continue to constrain report locations.

The TestKit suite executes the affected paths twice using
`--configuration-cache --configuration-cache-problems=fail`, and checks
store/reuse messages together with task results and files. The real-owner
suite runs with `M3_SMART_BUILDER_JAR` pointing to the source-pinned Maven JAR.
Its synthetic receiver fixtures test wire admission only; they are explicitly
not evidence that an external compiler or semantic proof executed.

Native CLI/JNI model execution requires independently supplied runtime and
model artifacts. Native M3File/M3AST planner integration, task-output cache
admission, and whole-fork distribution qualification remain separately scoped
work. These review tasks do not grant compilation-skipping authority.
