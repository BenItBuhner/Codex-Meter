plugins {
    id("com.android.library")
}

// The One UI 9 preview runs oneui-design (built for SESL8) on SESL9. A few resources that
// oneui-design reads through com.google.android.material.R were dropped from SESL9's material
// module, so this module puts them back under that same R package.
android {
    namespace = "com.google.android.material"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
