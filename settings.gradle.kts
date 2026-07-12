pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("vendor/m2") }
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.pkg.github.com/tribalfs/oneui-design") {
            credentials {
                username = System.getenv("GH_USERNAME") ?: "cached-build"
                password = System.getenv("GH_ACCESS_TOKEN") ?: "cached-build"
            }
        }
    }
}

rootProject.name = "Codex-Meter"
include(":app")
