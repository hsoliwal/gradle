/*
 * Copyright 2026 Gradle and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.tasks.compile;

import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.process.CommandLineArgumentProvider;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Explicit M3 compiler selection with Gradle-tracked provider contents.
 *
 * <p>Add an instance to JavaCompile.options.compilerArgumentProviders.
 * Provider files use Gradle's normal classpath fingerprinting; replacing bytes at
 * an unchanged path must invalidate the compilation task.
 */
@NullMarked
public final class M3ASTCompilerArguments implements CommandLineArgumentProvider {
    private final String providerClass;
    private final FileCollection providerClasspath;
    private final List<String> options;

    /**
     * Declare an opt-in provider and its compiler configuration.
     *
     * @param providerClass the exact source-built provider class
     * @param providerClasspath provider implementation and source-built dependencies
     * @param options additional provider-specific javac options
     */
    public M3ASTCompilerArguments(String providerClass, FileCollection providerClasspath, List<String> options) {
        this.providerClass = Objects.requireNonNull(providerClass, "providerClass");
        this.providerClasspath = Objects.requireNonNull(providerClasspath, "providerClasspath");
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
    }

    /** Exact implementation selection, tracked as a compilation input. */
    @Input
    public String getProviderClass() {
        return providerClass;
    }

    /** Provider byte contents, tracked using Gradle's classpath normalization. */
    @Classpath
    public FileCollection getProviderClasspath() {
        return providerClasspath;
    }

    /** Additional options, tracked in their declared order. */
    @Input
    public List<String> getOptions() {
        return options;
    }

    @Override
    public Iterable<String> asArguments() {
        List<String> arguments = new ArrayList<>();
        arguments.add("-XDm3AST=" + providerClass);
        arguments.add("-XDm3ASTPath=" + providerClasspath.getAsPath());
        arguments.addAll(options);
        return Collections.unmodifiableList(arguments);
    }
}
