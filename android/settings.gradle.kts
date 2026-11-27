pluginManagement {
    repositories {
        google()
        // Google's copy of Maven Central first: CI runners get HTTP 429 from Central itself, and
        // Gradle stops at a repository that errors instead of trying the next one. Anything the
        // mirror doesn't have yet (a 404) still comes from Central.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("vendor/m2") }
        google()
        // Google's copy of Maven Central first: CI runners get HTTP 429 from Central itself, and
        // Gradle stops at a repository that errors instead of trying the next one. Anything the
        // mirror doesn't have yet (a 404) still comes from Central.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        maven("https://jitpack.io")
        // skiko (Skia for the JVM and Android), which Glassé's kit draws with. Only JetBrains' repository
        // has skiko-android 0.156.1, so skiko never asks the repositories above: on a cold cache Gradle
        // tried Central first and failed the whole build on Central's HTTP 429 instead of its usual 404.
        exclusiveContent {
            forRepository { maven("https://packages.jetbrains.team/maven/p/cmp/dev") }
            filter { includeGroup("org.jetbrains.skiko") }
        }
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
include(":shared")
include(":wear")
include(":oneui9compat")
include(":glass")
