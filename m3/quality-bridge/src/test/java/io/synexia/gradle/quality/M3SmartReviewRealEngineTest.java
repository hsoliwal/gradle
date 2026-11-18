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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual Maven CLI consumers; synthetic source receipts test admission, never semantic proof. */
final class M3SmartReviewRealEngineTest {
    @TempDir Path root;

    @Test
    void preBuildAdviceRerunsTheOriginalOwnerWhenConfigurationIsReused() throws Exception {
        setup("");
        String first = runner("m3PreBuildAdvice").build().getOutput();
        assertTrue(first.contains("Configuration cache entry stored"), first);
        Path report = root.resolve("build/m3-smart/pre-build.json");
        String expected = Files.readString(report);
        assertTrue(expected.contains("\"provider\":\"none\""), expected);
        assertTrue(expected.contains("\"buildOutcome\":\"not-run\""), expected);
        assertTrue(expected.contains("\"adviceStatus\":\"disabled\""), expected);
        Files.delete(report);
        String second = runner("m3PreBuildAdvice").build().getOutput();
        assertTrue(second.contains("Reusing configuration cache"), second);
        assertEquals(expected, Files.readString(report));
    }

    @Test
    void postBuildAdvicePreservesExplicitFailureAndOmitsRawLogText() throws Exception {
        setup("tasks.named('m3PostBuildAdvice') {\n"
                + "  buildOutcome.set('failure')\n"
                + "  buildLog.set(layout.projectDirectory.file('build.log'))\n}\n");
        Files.writeString(root.resolve("build.log"), "[ERROR] cannot find symbol PRIVATE_LOG_MARKER\nBUILD FAILURE\n");
        runner("m3PostBuildAdvice").build();
        String report = Files.readString(root.resolve("build/m3-smart/post-build.json"));
        assertTrue(report.contains("\"buildOutcome\":\"failure\""), report);
        assertTrue(report.contains("\"logStatus\":\"available\""), report);
        assertFalse(report.contains("PRIVATE_LOG_MARKER"), report);
        assertFalse(report.contains("\"llmCandidateTrusted\":true"), report);
    }

    @Test
    void codeAgentAcceptsBoundFixturesAndRefusesSourceDriftAfterConfigurationCacheReuse() throws Exception {
        setup("tasks.named('m3CodeAgent') {\n"
                + "  sourceRoot.set(layout.projectDirectory.dir('src'))\n"
                + "  preparationReport.set(layout.projectDirectory.file('receipts/preparation.json'))\n"
                + "  atomPatternManifest.set(layout.projectDirectory.file('receipts/atoms.tsv'))\n"
                + "  recipeCatalog.set(layout.projectDirectory.file('receipts/recipes.tsv'))\n}\n");
        String source = writeSyntheticReceipts();
        runner("m3CodeAgent").build();
        Path report = root.resolve("build/m3-smart/code-agent.json");
        String original = Files.readString(report);
        assertTrue(original.contains("\"status\":\"awaiting-review\""), original);
        assertTrue(original.contains("\"recipeExecuted\":false"), original);
        assertTrue(original.contains("\"semanticProofAuthenticated\":false"), original);
        assertTrue(original.contains("iteration-loop"), original);
        assertEquals(source, Files.readString(root.resolve("src/Example.java")));
        Files.writeString(root.resolve("src/Example.java"), source + "// changed after receipt publication\n");
        String failed = runner("m3CodeAgent").buildAndFail().getOutput();
        assertTrue(failed.contains("Reusing configuration cache"), failed);
        assertTrue(failed.contains("M3 code-agent refused"), failed);
        String refused = Files.readString(report);
        assertTrue(refused.contains("\"status\":\"refused\""), refused);
        assertFalse(refused.contains("\"status\":\"proposed\""), refused);
    }

    @Test
    void invalidPreparationIsRejectedByTheOriginalOwner() throws Exception {
        setup("tasks.named('m3CodeAgent') {\n"
                + "  sourceRoot.set(layout.projectDirectory.dir('src'))\n"
                + "  preparationReport.set(layout.projectDirectory.file('receipts/preparation.json'))\n"
                + "  atomPatternManifest.set(layout.projectDirectory.file('receipts/atoms.tsv'))\n"
                + "  recipeCatalog.set(layout.projectDirectory.file('receipts/recipes.tsv'))\n}\n");
        writeSyntheticReceipts();
        Files.writeString(root.resolve("receipts/preparation.json"), "{}\n");
        String failed = runner("m3CodeAgent").buildAndFail().getOutput();
        assertTrue(failed.contains("M3 code-agent refused"), failed);
        assertTrue(Files.readString(root.resolve("build/m3-smart/code-agent.json"))
                .contains("\"status\":\"refused\""));
    }

    @Test
    void substitutedEngineIsRefusedBeforeItCanPublishAdvice() throws Exception {
        setup("");
        Files.writeString(root.resolve("engine.jar"), "changed engine bytes\n");
        String failed = runner("m3PreBuildAdvice").buildAndFail().getOutput();
        assertTrue(failed.contains("SHA-256"), failed);
        assertFalse(Files.exists(root.resolve("build/m3-smart/pre-build.json")));
    }

    private void setup(String configuration) throws Exception {
        String supplied = System.getenv("M3_SMART_BUILDER_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "requires the original Maven smart-builder JAR");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "explicit original engine must exist");
        Path engine = root.resolve("engine.jar");
        Files.copy(original, engine);
        String digest = hash(Files.readAllBytes(engine));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'm3-smart-review-consumer'\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.withType(io.synexia.gradle.quality.M3SmartReviewTask).configureEach {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "}\n" + configuration);
    }

    private GradleRunner runner(String task) {
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments(task, "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace");
    }

    /** Wire fixtures modeled on the Maven owner's tests; every attestation is explicitly synthetic. */
    private String writeSyntheticReceipts() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("receipts"));
        String source = "class Example { int sum(int[] a) { int n=0; for(int v:a) n+=v; return n; } }\n";
        Files.writeString(root.resolve("src/Example.java"), source);
        String sourceHash = hash(source);
        String sourceDigest = hash("Example.java\0" + sourceHash + "\n");
        String proof = hash("SYNTHETIC RECEIVER TEST ONLY; NO EXECUTED PROOF");
        StringBuilder checkpoint = new StringBuilder("round\tphase\tsource_path\tbefore_sha256\tafter_sha256"
                + "\tcompiler_root\tcontract_root\tbehavior_root\tjit_root\n");
        for (int round = 1; round <= 2; round++) {
            for (String phase : List.of("INVENTORY", "FILE_ATOMIZATION", "FILE_PATTERNIZATION", "FILE_DOCUMENTATION")) {
                checkpoint.append(round).append('\t').append(phase).append("\tsrc/Example.java\t")
                        .append(sourceHash).append('\t').append(sourceHash);
                for (int i = 0; i < 4; i++) checkpoint.append('\t').append(proof);
                checkpoint.append('\n');
            }
        }
        Files.writeString(root.resolve("receipts/synthetic-checkpoints.tsv"), checkpoint);
        Files.writeString(root.resolve("receipts/atoms.tsv"), "# m3.atom-pattern.manifest.v1\n"
                + "source_path\tinput_sha256\toutput_sha256\tcheckpoint_path\tcheckpoint_sha256\n"
                + "src/Example.java\t" + sourceHash + "\t" + sourceHash
                + "\treceipts/synthetic-checkpoints.tsv\t" + hash(checkpoint.toString()) + "\n");
        String preparation = """
                {"schema":"synexia.smart-build.preparation.v1","status":"COMPLETE",
                 "sourceRoot":"src","sourceDigest":"%s","sourceFiles":1,"sourceBytes":%d,
                 "files":[{"path":"Example.java","sha256":"%s","bytes":%d,"kind":"JAVA","status":"COMPLETE",
                  "reason":"NO_SUPPORTED_EXTRACTION","atomPreparation":{"status":"UNCHANGED:NO_SUPPORTED_EXTRACTION:SYNTHETIC_TEST",
                  "candidateSha256":"%s","proposedHelpers":0},"patterns":[{"ownerKind":"METHOD","owner":"Example#sum",
                  "pattern":"ITERATION","exactSha256":"%s","structuralSha256":"%s","authority":"CANDIDATE_ONLY"}]}],
                 "recipes":[{"name":"com.synexia.rewrite.M3PreparePrivatePrimitiveAtomsRecipe","status":"EXECUTED","executedFiles":1,"rows":1},
                  {"name":"com.synexia.rewrite.M3JavaPatternInventoryRecipe","status":"EXECUTED","executedFiles":1,"rows":1}],
                 "atomCandidateFiles":0,"proposedPrivateHelpers":0,"patternRowCount":1,"patternCounts":{"ITERATION":1},"unsupportedFiles":0,
                 "executionProvenance":{"runner":"com.synexia.rewrite.ChromeLlmRecipeExecution","ownerRevision":"%s",
                  "ownerSources":{"synthetic-receiver-fixture":"%s"},"javaRuntime":"21","execution":"OPENREWRITE_SDK",
                  "sourceMutationAuthority":false,"promotionAuthority":false,"physicalExtractionExecuted":false},
                 "remainingStages":["com.synexia.rewrite.M3AtomizeRecipe","com.synexia.rewrite.M3PatternizeRecipe"]}
                """.formatted(sourceDigest, source.getBytes(StandardCharsets.UTF_8).length, sourceHash,
                        source.getBytes(StandardCharsets.UTF_8).length, sourceHash, proof, proof, "0".repeat(40), proof);
        Files.writeString(root.resolve("receipts/preparation.json"), preparation);
        Files.writeString(root.resolve("receipts/recipes.tsv"), "recipe_id\tpattern\tscope\topenrewrite_recipe\timplementation_sha256\n"
                + "iteration-loop\tITERATION\tFILE\tcom.synexia.rewrite.IterationLoop\t" + proof + "\n");
        return source;
    }

    private static String hash(String text) throws Exception {
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
