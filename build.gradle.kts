plugins {
    application
    id("me.champeau.jmh") version "0.7.3"
}

group = "io.github.jackfurton"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.github.jackfurton.suitandtie.Main"
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

jmh {
    jmhVersion = "1.37"
}

// Benchmarks only run on demand (./gradlew jmh), but compile them in CI so they don't rot.
tasks.check {
    dependsOn(tasks.named("jmhClasses"))
}

tasks.test {
    useJUnitPlatform()
}
