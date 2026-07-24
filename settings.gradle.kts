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
                requested.id.id.startsWith("org.jetbrains.kotlin.") ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            }
        }
    }
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
    ":core-input",
    ":ai-protocol",
    ":brain-api",
    ":ai-brain",
    ":ai-runtime",
    ":benchmark",
)
