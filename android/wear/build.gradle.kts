plugins {
    id("com.android.application")
}

android {
    namespace = "dev.bennett.codexmeter"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.bennett.codexmeter"
        minSdk = 30
        targetSdk = 36
        versionCode = 20
        versionName = "2.4.3"
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
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.wear:wear:1.4.0")
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("androidx.wear.tiles:tiles:1.6.1")
    implementation("androidx.wear.protolayout:protolayout:1.4.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.4.1")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.1")
    implementation("androidx.concurrent:concurrent-futures:1.3.0")
    implementation("androidx.core:core:1.19.0")
    implementation("com.google.guava:guava:33.6.0-android")
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.3.0")
}
