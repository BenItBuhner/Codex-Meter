plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
}

// Every Java warning is an error. A deprecated call kept for older Android versions is marked
// @SuppressWarnings("deprecation") on the smallest scope, with the reason beside it. Only the
// dangling-doc-comments check is off: AGP generates BuildConfig.java with one.
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all,-dangling-doc-comments", "-Werror"))
    }
}
