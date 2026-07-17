plugins {
    id("com.android.application")
}

android {
    namespace = "dev.bennett.codexmeter"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bennett.codexmeter"
        minSdk = 30
        targetSdk = 36
        versionCode = 16
        versionName = "2.3.3"
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
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.core:core:1.15.0")
}
