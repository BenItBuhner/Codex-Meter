plugins {
    id("com.android.application")
}

android {
    namespace = "dev.bennett.codexmeter"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bennett.codexmeter"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "2.4.3"
        providers.gradleProperty("demoVersionCode").orNull?.toIntOrNull()?.let {
            versionCode = it
        }
        providers.gradleProperty("demoVersionName").orNull?.let {
            versionName = it
        }
        val updateApiUrl = providers.gradleProperty("demoUpdateUrl").orNull
            ?: "https://api.github.com/repos/BenItBuhner/Codex-Meter/releases?per_page=30" // pragma: allowlist secret
        buildConfigField("String", "UPDATE_API_URL",
            "\"${updateApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        buildConfig = true
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

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*")
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

configurations.configureEach {
    exclude(group = "androidx.core", module = "core")
    exclude(group = "androidx.core", module = "core-ktx")
    exclude(group = "androidx.appcompat", module = "appcompat")
    exclude(group = "androidx.fragment", module = "fragment")
    exclude(group = "androidx.recyclerview", module = "recyclerview")
    exclude(group = "androidx.preference", module = "preference")
    exclude(group = "androidx.coordinatorlayout", module = "coordinatorlayout")
    exclude(group = "androidx.customview", module = "customview")
    exclude(group = "androidx.drawerlayout", module = "drawerlayout")
    exclude(group = "androidx.viewpager", module = "viewpager")
    exclude(group = "androidx.viewpager2", module = "viewpager2")
    exclude(group = "com.google.android.material", module = "material")
}

dependencies {
    implementation(project(":shared"))
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("io.github.tribalfs:oneui-design:0.9.13+oneui8")
    implementation("io.github.oneuiproject:icons:1.1.0")
}
