// Copy the task configuration into an existing Gradle consumer build.
// Configure pluginManagement.includeBuild("../gradle/m3/quality-bridge") in settings.gradle.kts.
plugins {
    id("io.synexia.m3.quality")
    id("org.openrewrite.rewrite") version "7.41.0"
}

rewrite {
    activeRecipe("org.openrewrite.java.format.AutoFormat")
}

tasks.named<io.synexia.gradle.quality.M3CodeqlSarifTask>("m3CodeqlSarif") {
    databaseDirectory.set(layout.projectDirectory.dir("codeql-db-java"))
    querySuite.set("codeql/java-queries")
}

tasks.named<io.synexia.gradle.quality.M3SemgrepTask>("m3SemgrepScan") {
    localRulesFile.set(layout.projectDirectory.file("security/semgrep.yml"))
}

// Explicit gate: no tool is executed automatically by merely applying the M3 plugin.
tasks.register("reviewQuality") {
    dependsOn("m3CodeqlSarif", "m3RewritePreview", "m3SemgrepScan")
}

// Example C toolchain. Declare sources and outputs; no shell, no private scheduler.
tasks.register<io.synexia.gradle.quality.M3LanguageToolTask>("compileC") {
    executable.set("cc")
    workingDirectory.set(layout.projectDirectory)
    sourceFiles.from(fileTree("src/main/c") { include("**/*.c") })
    artifactDirectory.set(layout.buildDirectory.dir("native"))
    arguments.set(listOf("-c", "src/main/c/main.c", "-o", "build/native/main.o"))
}
