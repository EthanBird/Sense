plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":memory-protocol"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
