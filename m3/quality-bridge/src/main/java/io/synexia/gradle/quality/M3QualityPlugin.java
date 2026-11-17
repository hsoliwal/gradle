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

        // Explicit, originally executed XML reports only; no analyzer invocation.
        project.getTasks().register("m3NormalizeAnalyzerXml", M3NormalizeAnalyzerXmlTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Normalize existing Checkstyle/PMD XML using an explicitly pinned Java adapter");
            task.getJavaExecutable().convention(
                    project.getProviders().gradleProperty("m3.analysis.java").orElse("java"));
            task.getFormat().convention("CHECKSTYLE");
            task.getOriginalStatus().convention("PARTIAL");
            task.getProjectRoot().convention(project.getLayout().getProjectDirectory());
            task.getNormalizedReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/import/analysis.tsv"));
            task.getNormalizedSha256().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/import/analysis.sha256"));
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
            task.setDescription("Explicitly execute the pinned Maven polyglot engine from Gradle");
        });

        // The compiler/interpreter/transpiler/atomizer/patternizer catalog is
        // Maven-owned; this task only queries its versioned, pinned CLI output.
        project.getTasks().register("m3PolyglotProviders", M3PolyglotProvidersTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Inspect available M3 Maven tool providers without invoking them");
            task.getJavaExecutable().convention(
                    project.getProviders().gradleProperty("m3.polyglot.java").orElse("java"));
            task.getProviderJarSha256().convention(java.util.Map.of());
            task.getProviderReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/polyglot-providers.tsv"));
        });

        // Eclipse project descriptors have one Maven-owned parser. Gradle merely
        // supplies an explicit task boundary and immutable engine provenance.
        project.getTasks().register("m3EclipseModel", M3EclipseModelTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Inspect an Eclipse project through the pinned Maven project-model CLI");
            task.getJavaExecutable().convention(
                    project.getProviders().gradleProperty("m3.polyglot.java").orElse("java"));
            task.getEclipseProject().convention(project.getLayout().getProjectDirectory());
            task.getModelReport().convention(
                    project.getLayout().getBuildDirectory().file("reports/m3/eclipse-model.json"));
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
        task.getApproveRewriteApply().convention(false);
    }
}
