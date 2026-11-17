plugins {
    `java-gradle-plugin`
}

group = "io.synexia"
version = "0.1.0-SNAPSHOT"

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

gradlePlugin {
    plugins {
        create("m3Quality") {
            id = "io.synexia.m3.quality"
            implementationClass = "io.synexia.gradle.quality.M3QualityPlugin"
            displayName = "M3 Gradle quality and polyglot bridge"
            description = "Opt-in CodeQL, OpenRewrite, Semgrep and language-tool task integration"
        }
    }
}

repositories { mavenCentral() }

dependencies {
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
