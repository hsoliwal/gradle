# M3JDK compiler integration in this Gradle fork

This fork carries an optional M3JDK consumer through the existing compiler
options and worker protocol. Select M3JDK using Gradle's ordinary Java toolchain
or forked compiler configuration, then enable the option on the desired tasks:

```groovy
tasks.withType(JavaCompile).configureEach {
    options.m3AstEnabled.set(true)
}
```

The Kotlin DSL uses the same option:

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.m3AstEnabled.set(true)
}
```

The default is `false`. The property is a task input and is copied into
`MinimalJavaCompileOptions` for compilation workers. The normal
`JavaCompilerArgumentsBuilder` appends `-Xplugin:M3AST` unless an existing compiler
argument already selects that exact plugin. Compiler argument providers are
evaluated through the existing mechanism before this check.

Toolchain selection, javac task creation, annotation processing, Gradle's
incremental listeners and resource cleanup retain their normal paths. The same
argument builder supplies in-process, worker and command-line javac compilation.
This fork supplies no second parser, replacement compiler task or global AST
cache. The selected JDK must provide M3AST; enabling it with a compiler that does
not provide the plugin fails through the compiler's ordinary diagnostic.

## Optional compiler outputs

Add a single complete plugin argument to `options.compilerArgs`, or supply it
through `options.compilerArgumentProviders`. The explicit argument is preserved
when `m3AstEnabled` is also true:

```groovy
options.compilerArgs.add('-Xplugin:M3AST lossless trace=/owned/task-unique.tsv image=/owned/task-unique.m3prim slots=/owned/task-unique.slots.tsv fold-expressions')
```

The zero-argument plugin creates no output and does not eagerly request a
projection. `lossless` retains comments and end positions and disables string
folding before parsing. `trace` uses a new TSV path and fails if it already
exists. `image` writes the normal M3 primitive image only after a successful
compiler task. `slots` requires `image`; `fold-expressions` requires `image` and
opts into attributed constant-expression collection.

Each compiler invocation owns its paths, including separate tasks, projects,
incremental invocations, retries and parallel workers. A fixed trace filename
must not be shared across invocations. No path is assigned automatically.
Treat requested image/slot files as owned task outputs when configuring a task
that produces them. A JavaCompile task can be up-to-date or restored from cache;
in that case no compiler task runs and no new trace is created.

## Qualification

The argument builder's tests cover default behavior, worker option propagation,
exact plugin-name matching, explicit arguments, excluded main options and
annotation processor paths. Run the focused source test in this fork:

```sh
./gradlew :java-compiler-worker:test --tests '*JavaCompilerArgumentsBuilderTest'
```

Exercise a distribution containing these native source changes with real
annotation processing, incremental compilation, in-process and command-line
javac, compiler failure, M3 trace capture and primitive image output:

```sh
python3 m3/verify_ast_consumer.py --gradle-home /built/gradle --jdk-home /built/m3jdk21 --stock-jdk /toolchains/jdk21 --out /proof/gradle-unique
```

The script also proves that the default works on stock javac and that enabling
the native property on stock javac fails with its ordinary missing-plugin error.
Its receipt records runtime behavior; retain the distribution's separate source
build receipt to establish which fork sources were compiled.

The fork's own build currently requires the JDK stated in `CONTRIBUTING.md`
(JDK 25 at the pinned source revision). Its compiler workers retain their
existing Java compatibility; M3JDK's compiler runtime is Java 21.

A selective source rebuild against the pinned bootstrap Gradle distribution is
a useful integration check, but it is distinct from a complete distribution
built from this fork. A compiler-module image linked on stock OpenJDK is also
distinct from the full M3JDK VM acceptance gate. Record those distinctions in
the qualification receipt.
