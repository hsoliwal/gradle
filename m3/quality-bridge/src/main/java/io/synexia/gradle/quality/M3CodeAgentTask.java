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

import java.io.IOException;
import java.util.Map;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/** Source-bound recipe nominations through the original Maven CodeAgentMain, with no recipe execution. */
@UntrackedTask(because = "Recipe nominations require the original owner to revalidate current source-bound evidence")
public abstract class M3CodeAgentTask extends M3SmartReviewTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getRecipeCatalog();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getMachineEvidence();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getCandidateFile();
    @Input public abstract Property<Boolean> getRequireMachineEvidence();

    /** Publishes only the original owner's review-only recipe selection report. */
    @TaskAction
    public void reviewRecipes() {
        try {
            Map<String, String> options = reviewOptions();
            localInput(options, "--recipe-catalog", getRecipeCatalog(), 65_536);
            localInput(options, "--machine-evidence", getMachineEvidence(), 2_097_152);
            localInput(options, "--candidate-file", getCandidateFile(), 8_192);
            options.put("--require-machine-evidence", Boolean.toString(getRequireMachineEvidence().get()));
            runReview(M3SmartReviewCommands.CODE_AGENT_MAIN, options);
        } catch (IOException | IllegalArgumentException failure) {
            throw refused(failure);
        }
    }
}
