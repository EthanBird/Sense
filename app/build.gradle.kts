plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun releaseSigningValue(name: String): String? =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orNull
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSigningValue("SENSE_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("SENSE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("SENSE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("SENSE_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningValues.all { it != null }
check(releaseSigningValues.none { it != null } || releaseSigningConfigured) {
    "Sense release signing is partially configured; provide every SENSE_RELEASE_* value."
}
val releaseArtifactRequested = gradle.startParameter.taskNames.any { requestedTask ->
    requestedTask.substringAfterLast(':').lowercase() in
        setOf("assemblerelease", "bundlerelease", "packagerelease")
}
check(!releaseArtifactRequested || releaseSigningConfigured) {
    "A release artifact requires the persistent Sense release signing configuration."
}

android {
    namespace = "io.github.ethanbird.senseime"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ethanbird.senseime"
        minSdk = 29
        targetSdk = 36
        versionCode = 27
        versionName = "0.4.5.beta.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(checkNotNull(releaseStoreFile))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
                storeType = "PKCS12"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "DebugProbesKt.bin",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }
}

dependencies {
    implementation(project(":ai-runtime"))
    implementation(project(":ime-service"))
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
