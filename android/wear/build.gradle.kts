plugins {
    id("com.android.application")
}

android {
    namespace = "dev.bennett.codexmeter"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "dev.bennett.codexmeter"
        minSdk = 30
        targetSdk = 36
        versionCode = 30
        versionName = "2.9.0-alpha.38"
    }

    signingConfigs {
        create("localRelease") {
            val signingDir = rootProject.file(".local-signing")
            val keyStore = signingDir.resolve("codex-meter-local.p12")
            val passwordFile = signingDir.resolve("password")
            if (keyStore.isFile && passwordFile.isFile) {
                storeFile = keyStore
                storeType = "PKCS12"
                storePassword = passwordFile.readText().trim()
                keyAlias = "codexmeter"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        debug {
            // -PshrinkDebug=true shrinks the debug build (with its demo entry points) by the release
            // rules, so CI can open every screen on R8 output (ci/shrunk-smoke.sh).
            if (providers.gradleProperty("shrinkDebug").orNull == "true") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }
        release {
            // R8 removes unused code and resources: rules in proguard-rules.pro and the libraries'.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("localRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Known issues live in the baseline, which only shrinks; anything new fails the build.
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        // Newer versions are Dependabot's to raise; as lint errors they would break CI on any day.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.wear:wear:1.4.0")
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-proto:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.2")
    implementation("androidx.concurrent:concurrent-futures:1.3.0")
    implementation("androidx.core:core:1.19.1")
    implementation("com.google.guava:guava:33.8.0-android")
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.3.0")
}
