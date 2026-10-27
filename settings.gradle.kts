pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Maven Central rejects some egress IPs with 403; Google's mirror serves the same artifacts.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven(file("native/build/m2")) { name = "desuponCore" } }
            filter { includeGroup("moe.desupon") }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "Desupon"

include(
    ":core:model",
    ":core:parser",
    ":core:config",
    ":core:brain",
    ":core:turn",
    ":core:platform",
    ":data",
    ":service:vpn",
    ":service:turn",
    ":ui:theme",
    ":feature:home",
    ":feature:subscriptions",
    ":feature:settings",
    ":app",
)
