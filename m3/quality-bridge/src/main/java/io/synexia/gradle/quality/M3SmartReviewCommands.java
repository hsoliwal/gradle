/*
 * Copyright 2026 Gradle and contributors.
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Literal argument vectors for the original Maven advisory and recipe-nomination owners. */
public final class M3SmartReviewCommands {
    public static final String BUILD_HOOK_MAIN = "io.synexia.maven.smart.BuildHookMain";
    public static final String CODE_AGENT_MAIN = "io.synexia.maven.smart.CodeAgentMain";

    private static final Set<String> COMMON = Set.of("--project-dir", "--build-dir", "--source-root",
            "--preparation-report", "--atom-pattern-manifest", "--backend", "--chrome-llm-jar",
            "--llama-bin", "--jni-lib", "--model", "--timeout-seconds");
    private static final Set<String> HOOK = Set.of("--phase", "--build-outcome", "--log-file",
            "--require-atom-pattern", "--preparation-required", "--summarizer", "--summarizer-timeout-seconds");
    private static final Set<String> AGENT = Set.of("--recipe-catalog", "--machine-evidence",
            "--require-machine-evidence", "--candidate-file");
    private static final Set<String> NATIVE = Set.of("--chrome-llm-jar", "--llama-bin", "--jni-lib", "--model");

    private M3SmartReviewCommands() {}

    /**
     * Validates the selected public CLI contract and returns immutable, deterministic argv.
     * Native model commentary additionally requires the explicit {@code optionLlm} gate.
     *
     * @return a direct Java invocation, with every option and value in separate elements
     */
    public static List<String> command(String javaExecutable, File engineJar, List<File> providerJars,
            String mainClass, Map<String, String> supplied, boolean optionLlm) {
        boolean hook = BUILD_HOOK_MAIN.equals(mainClass);
        if (!hook && !CODE_AGENT_MAIN.equals(mainClass)) {
            throw new IllegalArgumentException("unsupported M3 smart review owner");
        }
        Map<String, String> options = new TreeMap<>(supplied);
        for (Map.Entry<String, String> option : options.entrySet()) {
            String value = option.getValue();
            if ((!COMMON.contains(option.getKey()) && !(hook ? HOOK : AGENT).contains(option.getKey()))
                    || value == null || value.isBlank() || value.length() > 4096
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("invalid M3 smart review option");
            }
        }
        required(options, "--project-dir");
        required(options, "--build-dir");
        String backend = options.getOrDefault("--backend", "none");
        if (!Set.of("none", "cli", "jni").contains(backend)) {
            throw new IllegalArgumentException("M3 review backend must be none, cli or jni");
        }
        if (backend.equals("none")) {
            if (NATIVE.stream().anyMatch(options::containsKey)) {
                throw new IllegalArgumentException("native model controls require an enabled native backend");
            }
        } else {
            if (!optionLlm) throw new IllegalArgumentException("native model commentary requires optionLlm=true");
            required(options, "--chrome-llm-jar");
            required(options, "--model");
            required(options, backend.equals("cli") ? "--llama-bin" : "--jni-lib");
            if (options.containsKey(backend.equals("cli") ? "--jni-lib" : "--llama-bin")) {
                throw new IllegalArgumentException("native model controls do not match the selected backend");
            }
        }
        integer(options, "--timeout-seconds", 1, 600);
        if (hook) {
            String phase = required(options, "--phase");
            if (phase.equals("pre-build")) {
                if (options.containsKey("--build-outcome") || options.containsKey("--log-file")) {
                    throw new IllegalArgumentException("pre-build advice cannot declare a completed build");
                }
            } else if (phase.equals("post-build")) {
                if (!Set.of("success", "failure").contains(required(options, "--build-outcome"))) {
                    throw new IllegalArgumentException("post-build advice requires the observed build outcome");
                }
            } else {
                throw new IllegalArgumentException("invalid M3 build advice phase");
            }
            bool(options, "--require-atom-pattern");
            boolean preparationRequired = bool(options, "--preparation-required");
            if (preparationRequired) {
                required(options, "--source-root");
                required(options, "--preparation-report");
            } else if (options.containsKey("--preparation-report")) {
                throw new IllegalArgumentException("preparation report requires preparationRequired=true");
            }
            if (options.containsKey("--summarizer-timeout-seconds")) {
                required(options, "--summarizer");
                integer(options, "--summarizer-timeout-seconds", 1, 600);
            }
        } else {
            required(options, "--source-root");
            required(options, "--preparation-report");
            required(options, "--atom-pattern-manifest");
            required(options, "--recipe-catalog");
            if (bool(options, "--require-machine-evidence")) required(options, "--machine-evidence");
            if (options.containsKey("--candidate-file") && !backend.equals("none")) {
                throw new IllegalArgumentException("a candidate file and native model are exclusive");
            }
        }
        List<String> argv = new ArrayList<>(List.of(M3QualityCommands.executable(javaExecutable),
                "-Dfile.encoding=UTF-8", "-cp", M3PolyglotCommands.classpath(engineJar, providerJars), mainClass));
        options.forEach((name, value) -> { argv.add(name); argv.add(value); });
        return List.copyOf(argv);
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing M3 review option: " + name);
        return value;
    }

    private static boolean bool(Map<String, String> options, String name) {
        String value = options.getOrDefault(name, "false");
        if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException("invalid boolean: " + name);
        return Boolean.parseBoolean(value);
    }

    private static void integer(Map<String, String> options, String name, int minimum, int maximum) {
        if (!options.containsKey(name)) return;
        try {
            int value = Integer.parseInt(options.get(name));
            if (value < minimum || value > maximum) throw new NumberFormatException();
        } catch (NumberFormatException refused) {
            throw new IllegalArgumentException("invalid M3 review limit: " + name, refused);
        }
    }
}
