#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Gradle and contributors
# SPDX-License-Identifier: Apache-2.0
"""Exercise this fork's native compiler option with real JavaCompile tasks.

Requires an M3JDK compiler image, a stock Java 21 JDK, and a Gradle distribution
containing this fork's source changes. No init script or replacement task is used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


SOURCE = '''package fixture;
public final class Main {
    public static final int VALUE = 21;
    public static void main(String[] args) {
        System.out.println("M3_GRADLE_CONSUMER_PASS:" + Generated.value());
    }
}
'''

PROCESSOR = '''package processor;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.StandardLocation;
@SupportedAnnotationTypes("*")
@SupportedOptions("fixture.marker")
public final class FixtureProcessor extends AbstractProcessor {
    private boolean generated;
    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.RELEASE_21; }
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (!generated && !round.processingOver()) {
            for (Element element : round.getRootElements()) {
                if (element.toString().equals("fixture.Main")) {
                    generated = true;
                    if (!"retained".equals(processingEnv.getOptions().get("fixture.marker")))
                        throw new AssertionError("Existing processor argument was lost");
                    try {
                        try (Writer writer = processingEnv.getFiler()
                                .createSourceFile("fixture.Generated", element).openWriter()) {
                            writer.write("package fixture; public final class Generated { public static int value() { return 21; } }");
                        }
                        try (Writer writer = processingEnv.getFiler()
                                .createResource(StandardLocation.CLASS_OUTPUT, "", "fixture-processor.txt", element).openWriter()) {
                            writer.write("PROCESSOR_PATH_AND_OPTION_RETAINED");
                        }
                    } catch (IOException failure) { throw new RuntimeException(failure); }
                    break;
                }
            }
        }
        return false;
    }
}
'''

BUILD = '''plugins { id 'java' }
dependencies { annotationProcessor files('processor.jar') }
tasks.withType(JavaCompile).configureEach {
    options.release.set(21)
    options.compilerArgs.add('-Afixture.marker=retained')
    options.m3AstEnabled.set(providers.gradleProperty('enableM3').map { Boolean.valueOf(it) }.orElse(false))
    def argument = providers.gradleProperty('m3Argument')
    if (argument.isPresent()) { options.compilerArgs.add(argument.get()) }
    def executable = providers.gradleProperty('javacExecutable')
    if (executable.isPresent()) {
        options.fork = true
        options.forkOptions.executable = executable.get()
    }
}
'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(arguments, directory, log, jdk, user_home, expected_success=True):
    environment = os.environ.copy()
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "GRADLE_OPTS", "JAVA_OPTS"):
        environment.pop(name, None)
    environment["JAVA_HOME"] = str(jdk)
    environment["GRADLE_USER_HOME"] = str(user_home)
    result = subprocess.run([str(arg) for arg in arguments], cwd=directory, env=environment,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
    log.write_text(result.stdout)
    if (result.returncode == 0) != expected_success:
        raise RuntimeError("Unexpected compiler result; see " + str(log))
    return result.stdout


def make_processor(output, stock, user_home):
    source = output / "processor-src/processor/FixtureProcessor.java"
    source.parent.mkdir(parents=True)
    source.write_text(PROCESSOR)
    classes = output / "processor-classes"
    classes.mkdir()
    run([stock / "bin/javac", "--release", "21", "-d", classes, source], output,
        output / "processor-compile.log", stock, user_home)
    archive = output / "processor.jar"
    with zipfile.ZipFile(archive, "w") as jar:
        for entry in sorted(classes.rglob("*.class")):
            jar.write(entry, entry.relative_to(classes).as_posix())
        jar.writestr("META-INF/services/javax.annotation.processing.Processor", "processor.FixtureProcessor\n")
        jar.writestr("META-INF/gradle/incremental.annotation.processors", "processor.FixtureProcessor,isolating\n")
    return archive


def project(output, name, processor):
    directory = output / name
    directory.mkdir()
    (directory / "settings.gradle").write_text("rootProject.name = 'm3-consumer-fixture'\n")
    (directory / "build.gradle").write_text(BUILD)
    source = directory / "src/main/java/fixture/Main.java"
    source.parent.mkdir(parents=True)
    source.write_text(SOURCE)
    shutil.copyfile(processor, directory / "processor.jar")
    return directory


def check_classes(directory, jdk, user_home):
    classes = directory / "build/classes/java/main"
    output = run([jdk / "bin/java", "-cp", classes, "fixture.Main"], directory,
                 directory / "run.log", jdk, user_home)
    if output.strip() != "M3_GRADLE_CONSUMER_PASS:21":
        raise ValueError("Compiled program result differs")
    if (classes / "fixture-processor.txt").read_text() != "PROCESSOR_PATH_AND_OPTION_RETAINED":
        raise ValueError("Annotation processor did not retain its configured path and argument")
    return {entry.relative_to(classes).as_posix(): digest(entry) for entry in sorted(classes.rglob("*.class"))}


def trace_stats(trace):
    lines = trace.read_text().splitlines()
    if not lines or not lines[0].startswith("# M3AST-1 actual javac events"):
        raise ValueError("Missing actual M3AST trace")
    stats = dict(re.findall(r"(\w+)=(\S+)", lines[-1]))
    if int(stats.get("parsed", "0")) < 1 or int(stats.get("extraSourceReads", "-1")) != 0:
        raise ValueError("Expected live compiler capture without a second source read")
    return stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle-home", type=Path, required=True)
    parser.add_argument("--jdk-home", type=Path, required=True)
    parser.add_argument("--stock-jdk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    gradle, jdk, stock = [path.resolve(strict=True) for path in (args.gradle_home, args.jdk_home, args.stock_jdk)]
    output = args.out.absolute()
    if any(output.resolve().is_relative_to(path) for path in (gradle, jdk, stock)):
        raise ValueError("Proof output cannot modify a supplied distribution")
    output.mkdir(parents=True, exist_ok=False)
    user_home = output / "gradle-user-home"
    base = [gradle / "bin/gradle", "--no-daemon", "--offline", "--max-workers=2", "--no-watch-fs", "--console=plain"]
    version = run(base + ["--version"], output, output / "gradle-version.log", jdk, user_home)
    processor = make_processor(output, stock, user_home)
    rows = []

    baseline = project(output, "default-stock", processor)
    run(base + ["compileJava"], baseline, baseline / "compile.log", stock, user_home)
    expected = check_classes(baseline, stock, user_home)
    rows.append({"case": "default-stock", "class_sha256": expected})

    unsupported = project(output, "enabled-stock-refusal", processor)
    refusal = run(base + ["-PenableM3=true", "compileJava"], unsupported,
                  unsupported / "compile.log", stock, user_home, expected_success=False)
    if "plug-in not found: M3AST" not in refusal:
        raise ValueError("Opt-in did not reach the stock compiler as an M3AST argument")
    rows.append({"case": "enabled-stock-refusal", "refusal": "plug-in not found: M3AST"})

    enabled = project(output, "enabled-m3", processor)
    run(base + ["-PenableM3=true", "compileJava"], enabled, enabled / "compile.log", jdk, user_home)
    if check_classes(enabled, jdk, user_home) != expected:
        raise ValueError("Default M3AST opt-in changed fixture bytecode")
    if not (enabled / "build/tmp/compileJava/previous-compilation-data.bin").is_file():
        raise ValueError("Gradle's incremental compiler state was not retained")
    up_to_date = run(base + ["-PenableM3=true", "compileJava"], enabled, enabled / "up-to-date.log", jdk, user_home)
    if ":compileJava UP-TO-DATE" not in up_to_date:
        raise ValueError("Unchanged compilation did not remain up-to-date")
    source = enabled / "src/main/java/fixture/Main.java"
    source.write_text(SOURCE.replace("VALUE = 21", "VALUE = 22"))
    incremental = run(base + ["--info", "-PenableM3=true", "compileJava"], enabled,
                      enabled / "incremental.log", jdk, user_home)
    if "Incremental compilation of" not in incremental:
        raise ValueError("Expected Gradle's existing incremental compilation path")
    check_classes(enabled, jdk, user_home)
    rows.append({"case": "enabled-m3", "class_sha256": expected, "up_to_date": True, "incremental_recompile": True})

    for name, command_line in (("api-trace", False), ("command-line-trace", True)):
        directory = project(output, name, processor)
        trace, image, slots = [directory / path for path in ("capture.tsv", "constants.m3prim", "slots.tsv")]
        argument = f"-Xplugin:M3AST lossless trace={trace} image={image} slots={slots} fold-expressions"
        command = base + ["-PenableM3=true", "-Pm3Argument=" + argument]
        if command_line:
            command += ["-PjavacExecutable=" + str(jdk / "bin/javac")]
        run(command + ["compileJava"], directory, directory / "compile.log", jdk, user_home)
        if check_classes(directory, jdk, user_home) != expected:
            raise ValueError("Trace/image consumer changed fixture bytecode")
        stats = trace_stats(trace)
        if stats.get("completed") != "true" or stats.get("errors") != "0" or stats.get("consumerFailed") != "false":
            raise ValueError("M3AST did not observe successful compilation")
        if int(stats.get("parsed", "0")) < 2 or "fixture.Generated" not in trace.read_text():
            raise ValueError("Compiler capture did not include the annotation processor's generated source")
        image_bytes = image.read_bytes()
        namespace = image_bytes[24:56].hex()
        if image_bytes[:8] != b"M3PRIM01" or not slots.read_text().startswith("# M3PRIM01 SHA-256 " + namespace):
            raise ValueError("M3 primitive image/slot output was not produced by the compiler consumer")
        rows.append({"case": name, "trace_stats": stats, "trace_sha256": digest(trace),
                     "image_sha256": digest(image), "slots_sha256": digest(slots), "class_sha256": expected})

    broken = project(output, "compiler-error", processor)
    (broken / "src/main/java/fixture/Main.java").write_text(SOURCE.replace("Generated.value()", "Unknown.value()"))
    trace, image = broken / "capture.tsv", broken / "constants.m3prim"
    argument = f"-Xplugin:M3AST trace={trace} image={image}"
    run(base + ["-PenableM3=true", "-Pm3Argument=" + argument, "compileJava"], broken,
        broken / "compile.log", jdk, user_home, expected_success=False)
    stats = trace_stats(trace)
    if int(stats.get("errors", "0")) == 0 or image.exists():
        raise ValueError("Compiler errors must prevent successful primitive image publication")
    rows.append({"case": "compiler-error", "trace_stats": stats, "image_absent": True})

    receipt = {"status": "REAL_GRADLE_NATIVE_COMPILER_OPTION_PASS", "cases": rows,
               "gradle_home": str(gradle), "jdk_home": str(jdk), "stock_jdk": str(stock),
               "gradle_version_output": version, "source_build_provenance": "SUPPLY_BUILD_RECEIPT_SEPARATELY",
               "full_m3_vm_acceptance": False}
    (output / "RECEIPT.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    print("M3_GRADLE_NATIVE_CONSUMER_PASS", output)


if __name__ == "__main__":
    main()
