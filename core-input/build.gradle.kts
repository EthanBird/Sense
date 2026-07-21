import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.register<JavaExec>("m0HostBenchmark") {
    group = "verification"
    description = "Measures the host-side reducer baseline and writes a JSON report."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M0HostBenchmark")
    args(rootProject.layout.projectDirectory.file("benchmarks/results/m0-host.json").asFile.absolutePath)
}

