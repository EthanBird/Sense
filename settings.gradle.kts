pluginManagement {
    val senseMavenProxy = System.getenv("SENSE_MAVEN_PROXY")?.trimEnd('/')
    repositories {
        if (senseMavenProxy != null) {
            maven("$senseMavenProxy/google") {
                isAllowInsecureProtocol = true
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("androidx\\..*")
                    includeGroupByRegex("com\\.google\\.testing.*")
                }
            }
            maven("$senseMavenProxy/central") {
                isAllowInsecureProtocol = true
                content {
                    excludeGroupByRegex("com\\.android.*")
                    excludeGroupByRegex("androidx\\..*")
                    excludeGroupByRegex("com\\.google\\.testing.*")
                }
            }
            maven("$senseMavenProxy/plugins") { isAllowInsecureProtocol = true }
        } else {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
    resolutionStrategy {
        eachPlugin {
            when {
                requested.id.id.startsWith("com.android.") ->
                    useModule("com.android.tools.build:gradle:${requested.version}")
                requested.id.id == "org.jetbrains.kotlin.plugin.compose" ->
                    useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${requested.version}")
                requested.id.id.startsWith("org.jetbrains.kotlin.") ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            }
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

val senseMavenProxy = System.getenv("SENSE_MAVEN_PROXY")?.trimEnd('/')

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (senseMavenProxy != null) {
            maven("$senseMavenProxy/google") {
                isAllowInsecureProtocol = true
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("androidx\\..*")
                    includeGroupByRegex("com\\.google\\.testing.*")
                }
            }
            maven("$senseMavenProxy/central") {
                isAllowInsecureProtocol = true
                content {
                    excludeGroupByRegex("com\\.android.*")
                    excludeGroupByRegex("androidx\\..*")
                    excludeGroupByRegex("com\\.google\\.testing.*")
                }
            }
        } else {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "Sense"

include(
    ":app",
    ":ime-service",
    ":ime-ui",
    ":agent-ui",
    ":ime-config",
    ":core-input",
    ":ai-protocol",
    ":brain-api",
    ":ai-brain",
    ":ai-runtime",
    ":memory-protocol",
    ":event-journal",
    ":benchmark",
)
