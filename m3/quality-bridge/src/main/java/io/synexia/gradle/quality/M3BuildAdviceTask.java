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

/** Explicit pre/post-build review through Maven's original standalone BuildHookMain. */
@UntrackedTask(because = "The original advisory owner revalidates current source and evidence on every invocation")
public abstract class M3BuildAdviceTask extends M3SmartReviewTask {
    @Input public abstract Property<String> getPhase();
    @Optional @Input public abstract Property<String> getBuildOutcome();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getBuildLog();
    @Input public abstract Property<Boolean> getRequireAtomPattern();
    @Input public abstract Property<Boolean> getPreparationRequired();
    @Optional @Input public abstract Property<String> getSummarizer();
    @Optional @Input public abstract Property<Integer> getSummarizerTimeoutSeconds();

    /** Writes the owner's review report; the post-build outcome must be supplied by the caller. */
    @TaskAction
    public void reviewBuild() {
        try {
            Map<String, String> options = reviewOptions();
            options.put("--phase", getPhase().get());
            if (getBuildOutcome().isPresent()) options.put("--build-outcome", getBuildOutcome().get());
            localInput(options, "--log-file", getBuildLog(), 16_000_000);
            options.put("--require-atom-pattern", Boolean.toString(getRequireAtomPattern().get()));
            options.put("--preparation-required", Boolean.toString(getPreparationRequired().get()));
            if (getSummarizer().isPresent()) options.put("--summarizer", getSummarizer().get());
            if (getSummarizerTimeoutSeconds().isPresent()) {
                options.put("--summarizer-timeout-seconds", Integer.toString(getSummarizerTimeoutSeconds().get()));
            }
            runReview(M3SmartReviewCommands.BUILD_HOOK_MAIN, options);
        } catch (IOException | IllegalArgumentException failure) {
            throw refused(failure);
        }
    }
}
