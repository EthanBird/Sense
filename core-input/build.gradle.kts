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
        rootProject.file(providers.gradleProperty("pinyinReport").getOrElse("benchmarks/results/m1-pinyin.json")).absolutePath,
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

tasks.register<JavaExec>("m5MixedInputBenchmark") {
    group = "verification"
    description = "Gates bilingual English and full-pinyin-plus-initials correctness/latency."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M5MixedInputBenchmark")
    args(
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_lexicon.bin").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_bigrams.bin").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_syllables.txt").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/english_lexicon.txt").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("benchmarks/results/m5-mixed-input.json").asFile.absolutePath,
    )
}

tasks.register<JavaExec>("m6InputPolishBenchmark") {
    group = "verification"
    description = "Gates English composition and late semantic Emoji/symbol candidates."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M6InputPolishBenchmark")
    args(
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/english_lexicon.txt").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("benchmarks/results/m6-input-polish.json").asFile.absolutePath,
    )
}

tasks.register<JavaExec>("m7ChineseSchemeBenchmark") {
    group = "verification"
    description = "Gates complete T9 decoding plus Wubi86 cold-load, memory and lookup performance."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M7ChineseSchemeBenchmark")
    args(
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/wubi86_lexicon.bin").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_lexicon.bin").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_bigrams.bin").asFile.absolutePath,
        rootProject.layout.projectDirectory.file("ime-service/src/main/assets/pinyin_syllables.txt").asFile.absolutePath,
        rootProject.file(providers.gradleProperty("chineseSchemeReport").getOrElse("benchmarks/results/m7-chinese-schemes.json")).absolutePath,
    )
}

tasks.register<JavaExec>("m8DailyInputBenchmark") {
    group = "verification"
    description = "Replays daily 26-key full-pinyin input through the progressive per-keystroke API."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M8DailyInputBenchmark")
    args(
        rootProject.file("ime-service/src/main/assets/pinyin_lexicon.bin").absolutePath,
        rootProject.file("ime-service/src/main/assets/pinyin_bigrams.bin").absolutePath,
        rootProject.file("ime-service/src/main/assets/pinyin_syllables.txt").absolutePath,
        rootProject.file("ime-service/src/main/assets/english_lexicon.txt").absolutePath,
        rootProject.file("benchmarks/replay/m8-daily-full-pinyin.tsv").absolutePath,
        rootProject.file(providers.gradleProperty("dailyInputReport").getOrElse("benchmarks/results/m8-daily-input.json")).absolutePath,
    )
}

tasks.register<JavaExec>("m9PersonalSentenceBenchmark") {
    group = "verification"
    description = "Gates learned words inside new sentences, restore/forget/isolation, and profiles 10k user rows."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M9PersonalSentenceBenchmark")
    args(
        rootProject.file("ime-service/src/main/assets/pinyin_lexicon.bin").absolutePath,
        rootProject.file("ime-service/src/main/assets/pinyin_bigrams.bin").absolutePath,
        rootProject.file("ime-service/src/main/assets/pinyin_syllables.txt").absolutePath,
        rootProject.file("ime-service/src/main/assets/english_lexicon.txt").absolutePath,
        rootProject.file("benchmarks/replay/m9-personal-word-sentences.tsv").absolutePath,
        rootProject.file("benchmarks/replay/m8-daily-full-pinyin.tsv").absolutePath,
        rootProject.file(providers.gradleProperty("personalSentenceReport").getOrElse("benchmarks/results/m9-personal-sentence.json")).absolutePath,
    )
}

tasks.register<JavaExec>("m10CharacterLmBenchmark") {
    group = "verification"
    description = "Checks the experimental corpus LM binary against Python and profiles transitions; requires prepared local assets."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M10CharacterLmBenchmark")
    args(
        rootProject.file(providers.gradleProperty("characterLmModel").getOrElse(".artifacts/input-quality/character-v1.scng")).absolutePath,
        rootProject.file(providers.gradleProperty("characterLmReference").getOrElse(".artifacts/input-quality/character-v1-reference.tsv")).absolutePath,
        rootProject.file(providers.gradleProperty("characterLmReport").getOrElse("benchmarks/results/m10-character-lm.json")).absolutePath,
    )
}

tasks.register<JavaExec>("m11LatticeLanguageBenchmark") {
    group = "verification"
    description = "Development-only real word-lattice LM ablation; requires the locally trained model."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M11LatticeLanguageBenchmark")
    args(
        rootProject.file("ime-service/src/main/assets/pinyin_lexicon.bin").absolutePath,
        rootProject.file("ime-service/src/main/assets/pinyin_bigrams.bin").absolutePath,
        rootProject.file("ime-service/src/main/assets/pinyin_syllables.txt").absolutePath,
        rootProject.file("ime-service/src/main/assets/english_lexicon.txt").absolutePath,
        rootProject.file("benchmarks/replay/m8-daily-full-pinyin.tsv").absolutePath,
        rootProject.file(providers.gradleProperty("characterLmModel").getOrElse(".artifacts/input-quality/character-v1.scng")).absolutePath,
        rootProject.file(providers.gradleProperty("latticeLmReport").getOrElse("benchmarks/results/m11-lattice-language.json")).absolutePath,
    )
}

tasks.register<JavaExec>("m12P2cBenchmark") {
    group = "verification"
    description = "Frozen P2C reconstruction runner. Use tools/evaluate_p2c.py for dev/test pin enforcement."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M12P2cBenchmark")
    val partition = providers.gradleProperty("p2cPartition").getOrElse("dev")
    args(
        rootProject.file("ime-service/src/main/assets/pinyin_lexicon.bin").absolutePath,
        rootProject.file("ime-service/src/main/assets/pinyin_bigrams.bin").absolutePath,
        rootProject.file("ime-service/src/main/assets/pinyin_syllables.txt").absolutePath,
        rootProject.file("ime-service/src/main/assets/english_lexicon.txt").absolutePath,
        rootProject.file("benchmarks/corpus/p2c-v1/$partition.tsv").absolutePath,
        rootProject.file(providers.gradleProperty("characterLmModel").getOrElse(".artifacts/input-quality/character-v1.scng")).absolutePath,
        providers.gradleProperty("p2cModes").getOrElse("legacy,lm0.25,lm0.5,lm1.0,lm2.0"),
        partition,
        rootProject.file(providers.gradleProperty("p2cReport").getOrElse("benchmarks/results/m12-p2c-$partition.json")).absolutePath,
    )
}

tasks.register<JavaExec>("m14ProgressiveEquivalenceBenchmark") {
    group = "verification"
    description = "Fingerprints every complete progressive result before/after algorithm-preserving optimizations."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M14ProgressiveEquivalenceBenchmark")
    args(rootProject.projectDir.absolutePath,
        rootProject.file(providers.gradleProperty("progressiveEquivalenceReport")
            .getOrElse(".artifacts/input-quality/m14-progressive.json")).absolutePath,
        providers.gradleProperty("progressiveIncludeKnownTypos").getOrElse("false"))
}

tasks.register<JavaExec>("m15TypoRecallBenchmark") {
    group = "verification"
    description = "Paired clean/synthetic-error recall diagnosis with production assets and no learning."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M15TypoRecallBenchmark")
    args(rootProject.projectDir.absolutePath,
        rootProject.file(providers.gradleProperty("typoReplay").getOrElse("benchmarks/corpus/typo-v1/dev.tsv")).absolutePath,
        rootProject.file(providers.gradleProperty("typoReport").getOrElse(".artifacts/input-quality/m15-typo.json")).absolutePath,
        providers.gradleProperty("typoGraphLimit").getOrElse("48"),
        providers.gradleProperty("typoCorrectionBoost").getOrElse("12"),
        rootProject.file(providers.gradleProperty("typoLexicon").getOrElse("ime-service/src/main/assets/pinyin_lexicon.bin")).absolutePath,
        providers.gradleProperty("typoOovFeature").getOrElse("-4"))
}

tasks.register<JavaExec>("m16SpellingGraphBenchmark") {
    group = "verification"
    description = "Diagnoses graph budget loss on known replay operations, using the production inventory."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M16SpellingGraphBenchmark")
    args(rootProject.projectDir.absolutePath,
        rootProject.file(providers.gradleProperty("spellingReplay").getOrElse("benchmarks/corpus/typo-v1/dev.tsv")).absolutePath,
        rootProject.file(providers.gradleProperty("spellingReport").getOrElse(".artifacts/input-quality/m16-graph.json")).absolutePath,
        providers.gradleProperty("spellingLimits").getOrElse("48,192"),
        providers.gradleProperty("spellingOperations").getOrElse("fuzzy"))
}

tasks.register<JavaExec>("m17CandidateScoreBenchmark") {
    group = "verification"
    description = "Exports production pre-ranker scores for source/LM diagnosis, no learning."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M17CandidateScoreBenchmark")
    args(rootProject.projectDir.absolutePath,
        rootProject.file(providers.gradleProperty("scoreReplay").getOrElse("benchmarks/corpus/typo-v1/dev.tsv")).absolutePath,
        rootProject.file(providers.gradleProperty("scoreReport").getOrElse(".artifacts/input-quality/m17-scores.jsonl")).absolutePath)
}

tasks.register<JavaExec>("m18LexiconLearningBenchmark") {
    group = "verification"
    description = "Production full-pinyin LM with synthetic learning/reuse/restore/forget across dictionary versions."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ethanbird.senseime.core.M18LexiconLearningBenchmark")
    args(rootProject.projectDir.absolutePath,
        rootProject.file(providers.gradleProperty("learningLexicon").getOrElse("ime-service/src/main/assets/pinyin_lexicon.bin")).absolutePath,
        rootProject.file(providers.gradleProperty("learningReport").getOrElse(".artifacts/input-quality/m18-learning.json")).absolutePath)
}
