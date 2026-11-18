/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Additive quality/toolchain task registrations; no forced plugin application. */
public final class M3QualityPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getTasks().register("m3CodeqlSarif", M3CodeqlSarifTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Run official CodeQL SARIF queries over an existing database");
            task.getCodeqlExecutable().convention(
                    project.getProviders().gradleProperty("m3.codeql.cli").orElse("codeql"));
            task.getThreads().convention(2);
            task.getSarifReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/codeql/results.sarif"));
        });

        project.getTasks().register("m3CodeqlEvidence", M3CodeqlEvidenceTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Run the separately installed M3 CodeQL semantic evidence coordinator");
            task.getJava21Executable().convention(
                    project.getProviders().gradleProperty("m3.java21.executable").orElse("java"));
            task.getCodeqlExecutable().convention(
                    project.getProviders().gradleProperty("m3.codeql.cli").orElse("codeql"));
            task.getThreads().convention(2);
            task.getRamMb().convention(3072);
            task.getTimeoutSeconds().convention(600);
            task.getEvidenceDirectory().convention(
                    project.getLayout().getBuildDirectory().dir("m3/codeql-evidence"));
        });

        project.getTasks().register("m3SemgrepScan", M3SemgrepTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Run installed Semgrep with local, explicitly selected rules");
            task.getSemgrepExecutable().convention(
                    project.getProviders().gradleProperty("m3.semgrep.cli").orElse("semgrep"));
            task.getSourceDirectory().convention(project.getLayout().getProjectDirectory());
            task.getJsonReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/semgrep/results.json"));
        });

        // No dependency on 'check': review of external analyzer claims is opt-in.
        project.getTasks().register("m3ReviewAnalysis", M3ReviewAnalysisTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Review an existing digest-pinned Maven M3 analyzer report");
            task.getPublishProblems().convention(false);
            task.getFailOnReportedErrors().convention(false);
            task.getFailOnIntroducedErrors().convention(false);
        });

        // Explicit, originally executed reports only; no analyzer invocation.
        project.getTasks().register("m3NormalizeAnalyzerXml", M3NormalizeAnalyzerXmlTask.class, task -> {
            analyzerDefaults(project, task, "CHECKSTYLE", "analysis");
            task.setDescription("Normalize existing Checkstyle/PMD XML using an explicitly pinned Java adapter");
        });

        // Maven #160 + #178 own the ECJ and javac parsers, error handling and
        // incomplete-log refusals. This additional Gradle task is opt-in.
        project.getTasks().register("m3NormalizeCompilerDiagnostics", M3NormalizeAnalyzerXmlTask.class, task -> {
            analyzerDefaults(project, task, "JAVAC", "compiler-analysis");
            task.setDescription("Normalize an existing ECJ XML or English UTF-8 javac log through Maven");
        });

        // Native Gradle InputChanges is the source owner; Maven's v1 wire is
        // only an exchange format and never grants goal or cache skipping.
        project.getTasks().register("m3IdeDeltaExport", M3IdeDeltaExportTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Export Gradle-owned source deltas in the Maven M3-IDE-DELTA-V1 wire format");
            task.getWorkspace().convention(project.getLayout().getProjectDirectory());
            task.getOutputRoot().convention(project.getLayout().getBuildDirectory());
            task.getSourceFiles().from(project.fileTree("src"));
            task.getProjectIdentifier().convention(
                    project.getRootProject().getName() + ":" + project.getPath());
            task.getDeltaReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/ide-delta.tsv"));
            task.getDeltaSha256().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/ide-delta.sha256"));
        });

        // Optional, read-only import; requires an operator-supplied original
        // digest and never promotes a delta into a Gradle build-cache decision.
        project.getTasks().register("m3IdeDeltaReview", M3IdeDeltaReviewTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Review a digest-pinned Maven IDE-delta v1 report without executing builds");
            task.getReportFile().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/ide-delta.tsv"));
            task.getExpectedSha256().convention(project.getProviders().gradleProperty("m3.ide.delta.sha256"));
            task.getExpectedProvider().convention("gradle");
            task.getExpectedProjectIdentifier().convention(
                    project.getRootProject().getName() + ":" + project.getPath());
        });

        // Both entry points delegate to Maven's original standalone polyglot CLI.
        // Neither task is attached to check/build or executes while the plugin is applied.
        project.getTasks().register("m3PolyglotPlan", M3PolyglotManifestTask.class, task -> {
            polyglotDefaults(project, task);
            task.getMode().convention("plan");
            task.setDescription("Plan the shared Maven/Gradle polyglot task manifest without executing tools");
        });
        project.getTasks().register("m3PolyglotRun", M3PolyglotManifestTask.class, task -> {
            polyglotDefaults(project, task);
            task.getMode().convention("run");
            task.getStateBackend().convention(
                    project.getProviders().gradleProperty("m3.polyglot.stateBackend").orElse("legacy"));
            task.setDescription("Explicitly execute the pinned Maven polyglot engine from Gradle");
        });

        // Reuse the Maven/JDK AST fingerprint producer and its canonical
        // MIndexDB fact owner. No source parse, cache key or receipt is forked.
        project.getTasks().register("m3MachineFingerprints", M3MachineFingerprintTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Produce canonical Java AST facts through the pinned Maven JDK21 CLI");
            task.getJavaExecutable().convention(
                    project.getProviders().gradleProperty("m3.fingerprints.java").orElse("java"));
            task.getProviderJarSha256().convention(java.util.Map.of());
            task.getProjectDirectory().convention(project.getLayout().getProjectDirectory());
            task.getSourceRoot().convention(project.getLayout().getProjectDirectory().dir("src/main/java"));
            task.getBuildDirectory().convention(project.getLayout().getBuildDirectory());
            task.getWorkers().convention(1);
            task.getAttributeTypes().convention(false);
            task.getAnalyzeFlow().convention(false);
            task.getModuleDb().convention(
                    project.getProviders().gradleProperty("m3.fingerprints.moduleDb").orElse("auto"));
        });

        // All capability IDs, including Maven #203 linker/optimizer/assembler,
        // are Maven-owned; only query the versioned and SHA-pinned CLI output.
        project.getTasks().register("m3PolyglotProviders", M3PolyglotProvidersTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Inspect available M3 Maven tool providers without invoking them");
            task.getJavaExecutable().convention(
                    project.getProviders().gradleProperty("m3.polyglot.java").orElse("java"));
            task.getProviderJarSha256().convention(java.util.Map.of());
            task.getProviderReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/polyglot-providers.tsv"));
        });

        // Maven #210 owns the original LLVM executable-byte evidence schema.
        // Gradle adds opt-in, untracked scan/verify task boundaries only; neither
        // task is wired to compilation or cached from incomplete native inputs.
        project.getTasks().register("m3LlvmToolchainScan", M3LlvmToolchainEvidenceTask.class, task -> {
            llvmEvidenceDefaults(project, task, "scan");
            task.setDescription("Seal selected LLVM executable bytes via Maven's original toolchain CLI");
        });
        project.getTasks().register("m3LlvmToolchainVerify", M3LlvmToolchainEvidenceTask.class, task -> {
            llvmEvidenceDefaults(project, task, "verify");
            task.setDescription("Verify Maven's original LLVM toolchain byte manifest without running tools");
        });

        // Eclipse project descriptors have one Maven-owned parser. Gradle merely
        // supplies an explicit task boundary and immutable engine provenance.
        project.getTasks().register("m3EclipseModel", M3EclipseModelTask.class, task -> {
            eclipseDefaults(project, task);
            task.getReportMode().convention("model");
            task.setDescription("Inspect an Eclipse project through the pinned Maven project-model CLI");
            task.getModelReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/eclipse-model.json"));
        });

        // Arbitrary builder IDs and natures are inventoried by Maven's SPI.
        // Unbound providers remain unbound; this task cannot invoke an Eclipse builder.
        project.getTasks().register("m3EclipseBuilders", M3EclipseModelTask.class, task -> {
            eclipseDefaults(project, task);
            task.getReportMode().convention("owners");
            task.setDescription("Inspect Eclipse JDT/CDT/Rust and arbitrary language builder ownership");
            task.getModelReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/eclipse-builders.json"));
        });

        // Eclipse itself exports the actual IBuildConfiguration of the owning
        // builder. This separate packet must not be guessed from a .cproject.
        project.getTasks().register("m3EclipseBuildContext", M3EclipseModelTask.class, task -> {
            eclipseDefaults(project, task);
            task.getReportMode().convention("context");
            task.setDescription("Read an explicit Eclipse-owner build-configuration packet via pinned Maven CLI");
            task.getContextPacket().convention(
                    project.getLayout().getProjectDirectory().file("target/ide/build-context.ide"));
            task.getModelReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/eclipse-build-context.json"));
        });

        // The single original Eclipse invocation contains both selected
        // IBuildConfiguration and captured IResourceDelta. No Gradle compile or
        // cache decision is inferred from this advisory metadata.
        project.getTasks().register("m3EclipseBuildHandoff", M3EclipseModelTask.class, task -> {
            eclipseDefaults(project, task);
            task.getReportMode().convention("handoff");
            task.setDescription("Inspect one Eclipse build's selected configuration and original delta");
            task.getHandoffPacket().convention(
                    project.getLayout().getProjectDirectory().file("target/ide/build-handoff.ide"));
            task.getModelReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/eclipse-build-handoff.json"));
        });

        // Compare source-owner builder/nature declarations with the current
        // Eclipse project model, using Maven's original parser. Any divergence
        // or incomplete delta requires conservative reconciliation; a match
        // is never a build-cache admission.
        project.getTasks().register("m3EclipseReconcile", M3EclipseModelTask.class, task -> {
            eclipseDefaults(project, task);
            task.getReportMode().convention("reconcile");
            task.setDescription("Reconcile current Eclipse builders/natures with one source-owned handoff");
            task.getHandoffPacket().convention(
                    project.getLayout().getProjectDirectory().file("target/ide/build-handoff.ide"));
            task.getModelReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/eclipse-reconciliation.json"));
        });

        // Optional JDT-origin compiler fingerprints are separately versioned
        // and must belong to the same Eclipse invocation and configuration.
        // Maven validates the original packet; Gradle never replaces ECJ.
        project.getTasks().register("m3EclipseJdtFacts", M3EclipseModelTask.class, task -> {
            eclipseDefaults(project, task);
            task.getReportMode().convention("jdt");
            task.setDescription("Review original JDT compiler options and classpath metadata");
            task.getHandoffPacket().convention(
                    project.getLayout().getProjectDirectory().file("target/ide/build-handoff.ide"));
            task.getJdtPacket().convention(
                    project.getLayout().getProjectDirectory().file("target/ide/jdt-compiler-facts.ide"));
            task.getModelReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/eclipse-jdt-facts.json"));
        });

        // Source-owned CDT scanner evidence is independent of JDT classpaths.
        // Keep native CDT configuration IDs distinct from Eclipse and active CDT.
        project.getTasks().register("m3EclipseCdtFacts", M3EclipseModelTask.class, task -> {
            eclipseDefaults(project, task);
            task.getReportMode().convention("cdt");
            task.setDescription("Review original CDT scanner, source and build configuration evidence");
            task.getHandoffPacket().convention(
                    project.getLayout().getProjectDirectory().file("target/ide/build-handoff.ide"));
            task.getCdtPacket().convention(
                    project.getLayout().getProjectDirectory().file("target/ide/cdt-compiler-facts.ide"));
            task.getModelReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/eclipse-cdt-facts.json"));
        });

        // Corrosion uses the configured original Cargo CLI. Only the native
        // owner can explicitly produce locked/offline resolved Cargo metadata;
        // this optional task reads the Maven-validated packet, never runs Rust.
        project.getTasks().register("m3EclipseCargoFacts", M3EclipseModelTask.class, task -> {
            eclipseDefaults(project, task);
            task.getReportMode().convention("cargo");
            task.setDescription("Review original Corrosion/Cargo offline metadata evidence");
            task.getHandoffPacket().convention(
                    project.getLayout().getProjectDirectory().file("target/ide/build-handoff.ide"));
            task.getCargoPacket().convention(
                    project.getLayout().getProjectDirectory().file("target/ide/cargo-build-facts.ide"));
            task.getModelReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/eclipse-cargo-facts.json"));
        });

        // The Maven #184 project DB owns the data and deterministic retrieval.
        // These optional tasks neither reimplement indexes nor affect build/check.
        project.getTasks().register("m3ProjectDbIngest", M3ProjectDbTask.class, task -> {
            projectDbDefaults(project, task);
            task.getMode().convention("ingest");
            task.getResultReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/project-db/ingest.json"));
            task.setDescription("Ingest Maven project build evidence through the pinned M3IndexDB CLI");
        });
        project.getTasks().register("m3ProjectDbQuery", M3ProjectDbTask.class, task -> {
            projectDbDefaults(project, task);
            task.getMode().convention("query");
            task.getResultReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/project-db/query.json"));
            task.setDescription("Query original Maven project database using CPU-only deterministic retrieval");
        });

        // Maven's standalone Java/JNI review owners retain evidence admission
        // and report formats. Register lazily, without executing or attaching
        // review work to the native Gradle build lifecycle.
        project.getTasks().register("m3PreBuildAdvice", M3BuildAdviceTask.class, task -> {
            smartReviewDefaults(project, task);
            task.getPhase().convention("pre-build");
            task.getRequireAtomPattern().convention(false);
            task.getPreparationRequired().convention(false);
            task.setDescription("Review existing project facts through the pinned Maven pre-build adviser");
        });
        project.getTasks().register("m3PostBuildAdvice", M3BuildAdviceTask.class, task -> {
            smartReviewDefaults(project, task);
            task.getPhase().convention("post-build");
            task.getRequireAtomPattern().convention(false);
            task.getPreparationRequired().convention(false);
            task.getBuildOutcome().convention(project.getProviders().gradleProperty("m3.smart.buildOutcome"));
            task.setDescription("Review an explicitly observed build outcome through the pinned Maven adviser");
        });
        project.getTasks().register("m3CodeAgent", M3CodeAgentTask.class, task -> {
            smartReviewDefaults(project, task);
            task.getSourceRoot().convention(project.getLayout().getProjectDirectory().dir("src/main/java"));
            task.getRequireMachineEvidence().convention(false);
            task.setDescription("Nominate existing recipes through the pinned Maven source-bound coding adviser");
        });

        // An original OpenRewrite plugin owns recipe discovery, dry-run, and apply.
        // The alias exists only if that plugin is explicitly applied in the project.
        project.getPluginManager().withPlugin("org.openrewrite.rewrite", applied -> {
            project.getTasks().register("m3RewritePreview", task -> {
                task.setGroup("verification");
                task.setDescription("Delegate to OpenRewrite's original rewriteDryRun task");
                task.dependsOn(project.getTasks().named("rewriteDryRun"));
            });
        });
    }

    private static void eclipseDefaults(Project project, M3EclipseModelTask task) {
        task.setGroup("verification");
        task.getProviderJarSha256().convention(java.util.Map.of());
        task.getJavaExecutable().convention(
                project.getProviders().gradleProperty("m3.polyglot.java").orElse("java"));
        task.getEclipseProject().convention(project.getLayout().getProjectDirectory());
    }

    private static void projectDbDefaults(Project project, M3ProjectDbTask task) {
        task.setGroup("verification");
        task.getJavaExecutable().convention(
                project.getProviders().gradleProperty("m3.projectdb.java").orElse("java"));
        task.getAdditionalJarSha256().convention(java.util.Map.of());
        task.getSourceProject().convention(project.getLayout().getProjectDirectory());
        task.getStorageDirectory().convention(
                project.getLayout().getBuildDirectory().dir("m3/project-db"));
        task.getTopK().convention(5);
        task.getQuestion().convention("");
    }

    private static void smartReviewDefaults(Project project, M3SmartReviewTask task) {
        task.setGroup("verification");
        task.getJavaExecutable().convention(
                project.getProviders().gradleProperty("m3.smart.java").orElse("java"));
        task.getProjectDirectory().convention(project.getLayout().getProjectDirectory());
        task.getBuildDirectory().convention(project.getLayout().getBuildDirectory());
        task.getProviderJarSha256().convention(java.util.Map.of());
        task.getTimeoutSeconds().convention(120);
        task.getBackend().convention(project.getProviders().gradleProperty("m3.smart.backend").orElse("none"));
        task.getOptionLlm().convention(project.getProviders().gradleProperty("optionllm")
                .map(M3QualityPlugin::optionLlm).orElse(false));
    }

    private static boolean optionLlm(String value) {
        if (value.isEmpty() || "true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new org.gradle.api.GradleException("optionllm must be empty, true or false");
    }

    private static void polyglotDefaults(Project project, M3PolyglotManifestTask task) {
        task.setGroup("build");
        task.getJavaExecutable().convention(
                project.getProviders().gradleProperty("m3.polyglot.java").orElse("java"));
        task.getProviderJarSha256().convention(java.util.Map.of());
        task.getWorkspace().convention(project.getLayout().getProjectDirectory());
        task.getManifestFile().convention(project.getLayout().getProjectDirectory().file("m3-build.tsv"));
        task.getTarget().convention("all");
        task.getTimeoutSeconds().convention(900);
        task.getIncremental().convention(false);
        task.getProviderReuse().convention(false);
        task.getStateBackend().convention("legacy");
        task.getM3DbModuleAccess().convention("none");
        task.getApproveRewriteApply().convention(false);
    }

    private static void llvmEvidenceDefaults(Project project, M3LlvmToolchainEvidenceTask task,
            String mode) {
        task.setGroup("verification");
        task.getMode().convention(mode);
        task.getSelectedTools().convention(
                project.getProviders().gradleProperty("m3.llvm.tools").orElse("opt"));
        task.getJavaExecutable().convention(
                project.getProviders().gradleProperty("m3.polyglot.java").orElse("java"));
        task.getEvidenceManifest().convention(
                project.getLayout().getBuildDirectory().file("reports/m3/llvm-toolchain.tsv"));
    }

    private static void analyzerDefaults(Project project, M3NormalizeAnalyzerXmlTask task,
            String format, String reportName) {
        task.setGroup("verification");
        task.getJavaExecutable().convention(
                project.getProviders().gradleProperty("m3.analysis.java").orElse("java"));
        task.getFormat().convention(format);
        task.getOriginalStatus().convention("PARTIAL");
        task.getProjectRoot().convention(project.getLayout().getProjectDirectory());
        task.getNormalizedReport().convention(
                project.getLayout().getBuildDirectory().file("reports/m3/import/" + reportName + ".tsv"));
        task.getNormalizedSha256().convention(
                project.getLayout().getBuildDirectory().file("reports/m3/import/" + reportName + ".sha256"));
    }
}
