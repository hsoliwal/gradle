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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class M3SmartReviewCommandsTest {
    @Test
    void directArgumentsPreservePathsAndDoNotDependOnOptionOrder() {
        Map<String, String> options = base();
        options.put("--phase", "pre-build");
        options.put("--source-root", new File("workspace/source with spaces").getAbsolutePath());
        List<String> command = command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false);
        assertEquals(command, command(M3SmartReviewCommands.BUILD_HOOK_MAIN, new TreeMap<>(options), false));
        assertEquals("java", command.get(0));
        assertEquals(M3SmartReviewCommands.BUILD_HOOK_MAIN, command.get(4));
        assertEquals(options.get("--source-root"), command.get(command.indexOf("--source-root") + 1));
        assertFalse(command.contains("sh"));
        assertThrows(UnsupportedOperationException.class, () -> command.add("extra"));
    }

    @Test
    void preBuildCannotInventACompletedOutcomeAndPostBuildRequiresOne() {
        Map<String, String> options = base();
        options.put("--phase", "post-build");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false));
        options.put("--build-outcome", "failure");
        assertTrue(command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false).contains("failure"));
        options.put("--phase", "pre-build");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false));
        options.remove("--build-outcome");
        options.put("--log-file", "build.log");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false));
    }

    @Test
    void onlyExplicitNativeBackendsCanUseNativeControls() {
        Map<String, String> options = base();
        options.put("--phase", "pre-build");
        options.put("--backend", "chrome");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, true));
        options.put("--backend", "jni");
        options.put("--chrome-llm-jar", "provider.jar");
        options.put("--jni-lib", "provider.so");
        options.put("--model", "model.gguf");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false));
        assertTrue(command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, true).contains("provider.so"));
        options.put("--llama-bin", "llama-cli");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, true));
        options.put("--backend", "none");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, true));
    }

    @Test
    void recipeNominationRequiresCanonicalEvidenceAndHonorsStrictMachinePolicy() {
        Map<String, String> options = agent();
        assertTrue(command(M3SmartReviewCommands.CODE_AGENT_MAIN, options, false).contains("recipes.tsv"));
        options.put("--require-machine-evidence", "true");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.CODE_AGENT_MAIN, options, false));
        options.put("--machine-evidence", "machine.tsv");
        assertTrue(command(M3SmartReviewCommands.CODE_AGENT_MAIN, options, false).contains("machine.tsv"));
        options.remove("--preparation-report");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.CODE_AGENT_MAIN, options, false));
    }

    @Test
    void candidateFileCannotBeCombinedWithModelInference() {
        Map<String, String> options = agent();
        options.put("--candidate-file", "candidate.json");
        assertTrue(command(M3SmartReviewCommands.CODE_AGENT_MAIN, options, false).contains("candidate.json"));
        options.put("--backend", "cli");
        options.put("--chrome-llm-jar", "provider.jar");
        options.put("--llama-bin", "llama-cli");
        options.put("--model", "model.gguf");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.CODE_AGENT_MAIN, options, true));
    }

    @Test
    void unknownCommandsControlCharactersAndUnboundedTimeoutsAreRefused() {
        Map<String, String> options = base();
        options.put("--phase", "pre-build");
        assertThrows(IllegalArgumentException.class, () -> command("arbitrary.Main", options, false));
        options.put("--shell-command", "echo unsafe");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false));
        options.remove("--shell-command");
        options.put("--timeout-seconds", "601");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false));
        options.put("--timeout-seconds", "120");
        options.put("--project-dir", "workspace\nchanged");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false));
    }

    @Test
    void summarizerRemainsOneOriginalOwnerArgumentWithItsOwnDeadline() {
        Map<String, String> options = base();
        options.put("--phase", "pre-build");
        options.put("--summarizer-timeout-seconds", "600");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false));
        String summarizer = "\"/tools with spaces/summarizer\" --bounded";
        options.put("--summarizer", summarizer);
        List<String> command = command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false);
        assertEquals(summarizer, command.get(command.indexOf("--summarizer") + 1));
        options.put("--summarizer-timeout-seconds", "601");
        assertThrows(IllegalArgumentException.class, () -> command(M3SmartReviewCommands.BUILD_HOOK_MAIN, options, false));
    }

    private static Map<String, String> base() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("--project-dir", new File("workspace").getAbsolutePath());
        result.put("--build-dir", new File("workspace/build").getAbsolutePath());
        result.put("--backend", "none");
        return result;
    }

    private static Map<String, String> agent() {
        Map<String, String> result = base();
        result.put("--source-root", "src");
        result.put("--preparation-report", "preparation.json");
        result.put("--atom-pattern-manifest", "atoms.tsv");
        result.put("--recipe-catalog", "recipes.tsv");
        return result;
    }

    private static List<String> command(String main, Map<String, String> options, boolean enabled) {
        return M3SmartReviewCommands.command("java", new File("engine.jar"), List.of(), main, options, enabled);
    }
}
