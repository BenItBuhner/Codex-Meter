plugins {
    id("com.android.application")
}

// Samsung's SESL9 libraries (One UI 9) back the whole app. The Appearance setting picks between
// the classic look and the One UI 9 chrome at runtime (see DesignChrome); SESL8 and SESL9 share
// class names, so one APK can only hold one of them. SESL9 is published only on GitHub Packages
// and vendored in vendor/m2 (ci/vendor-sesl9.sh). It ships Java 24 bytecode, so builds need JDK 25.
val sesl9 = mapOf(
    "sesl.androidx.appcompat:appcompat" to "1.8.0+1.0.38-sesl9+rev4",
    "sesl.androidx.coordinatorlayout:coordinatorlayout" to "1.3.0+1.0.6-sesl9+rev0",
    "sesl.androidx.core:core" to "1.19.0+1.0.29-sesl9+rev4",
    "sesl.androidx.customview:customview" to "1.2.0-rc01+1.0.1-sesl9+rev0",
    "sesl.androidx.drawerlayout:drawerlayout" to "1.2.0+1.0.5-sesl9+rev1",
    "sesl.androidx.fragment:fragment" to "1.9.0+1.0.6-sesl9+rev1",
    "sesl.androidx.indexscroll:indexscroll" to "1.0.2+1.0.2-sesl9+rev0",
    "sesl.com.google.android.material:material" to "1.14.0+1.0.47-sesl9+rev9",
    "sesl.androidx.picker:picker-app" to "1.0.17+1.0.17-sesl9+rev2",
    "sesl.androidx.picker:picker-basic" to "1.0.8+1.0.8-sesl9+rev1",
    "sesl.androidx.picker:picker-color" to "1.0.8+1.0.8-sesl9+rev0",
    "sesl.androidx.preference:preference" to "1.2.1+1.0.5-sesl9+rev0",
    "sesl.androidx.recyclerview:recyclerview" to "1.4.0+1.0.25-sesl9+rev4",
    "sesl.androidx.slidingpanelayout:slidingpanelayout" to "1.2.0+1.0.2-sesl9+rev0",
    "sesl.androidx.swiperefreshlayout:swiperefreshlayout" to "1.2.0-alpha01+1.0.2-sesl9+rev1",
    "sesl.androidx.viewpager:viewpager" to "1.1.0-beta01+1.0.1-sesl9+rev0",
    "sesl.androidx.viewpager2:viewpager2" to "1.1.0+1.0.5-sesl9+rev0",
)

android {
    namespace = "dev.bennett.codexmeter"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.bennett.codexmeter"
        minSdk = 26
        targetSdk = 36
        versionCode = 30
        versionName = "2.9.0-alpha.6"
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
        // Antigravity's Google sign-in client comes from the build environment, never the repo.
        fun buildSecret(name: String): String = (providers.gradleProperty(name).orNull
            ?: System.getenv(name) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "ANTIGRAVITY_CLIENT_ID",
            "\"${buildSecret("ANTIGRAVITY_CLIENT_ID")}\"")
        buildConfigField("String", "ANTIGRAVITY_CLIENT_SECRET",
            "\"${buildSecret("ANTIGRAVITY_CLIENT_SECRET")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // SESL8 values for the classic design (see DesignChrome.wrap).
    sourceSets.getByName("main").res.directories.add("src/main/res-classic")

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
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("io.github.tribalfs:oneui-design:0.9.20+oneui8")
    implementation("io.github.oneuiproject:icons:1.1.0")
    implementation(project(":oneui9compat"))
    sesl9.forEach { (module, pinned) ->
        implementation(module) { version { strictly(pinned) } }
    }
}
