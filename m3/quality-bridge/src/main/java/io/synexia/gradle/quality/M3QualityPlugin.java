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
}
