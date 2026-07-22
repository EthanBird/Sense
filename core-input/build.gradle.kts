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

tasks.register<JavaExec>("m1PinyinBenchmark") {
    group = "verification"
    description = "Measures production pinyin lexicon load and lookup latency."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M1PinyinBenchmark")
    args(
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_lexicon.bin").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("benchmarks/results/m1-pinyin.json").asFile.absolutePath,
    )
}

tasks.register<JavaExec>("m2AdaptiveBenchmark") {
    group = "verification"
    description = "Measures statistical short codes, typo correction and initials learning."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M2AdaptiveBenchmark")
    args(
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_lexicon.bin").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_syllables.txt").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("benchmarks/results/m2-adaptive.json").asFile.absolutePath,
    )
}

tasks.register<JavaExec>("m3SentenceBenchmark") {
    group = "verification"
    description = "Runs the M3 context-ranking sentence replay and latency gate."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M3SentenceBenchmark")
    args(
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_lexicon.bin").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_bigrams.bin").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("benchmarks/replay/m3-sentences.tsv").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("benchmarks/results/m3-sentence.json").asFile.absolutePath,
    )
}

tasks.register<JavaExec>("m4CoreBenchmark") {
    group = "verification"
    description = "Gates M4 initials lookup and progressive segmentation correctness/latency."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M4CoreBenchmark")
    args(
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_lexicon.bin").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_bigrams.bin").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_syllables.txt").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("benchmarks/replay/m4-core.tsv").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("benchmarks/results/m4-core.json").asFile.absolutePath,
    )
}
