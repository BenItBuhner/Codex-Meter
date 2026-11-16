plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
}

// Every Java warning is an error. A deprecated call kept for older Android versions is marked
// @SuppressWarnings("deprecation") on the smallest scope, with the reason beside it. Only the
// dangling-doc-comments check is off: AGP generates BuildConfig.java with one. JDKs before 23
// don't have that check, and reject its name.
val lint = if (JavaVersion.current() >= JavaVersion.VERSION_23) "-Xlint:all,-dangling-doc-comments" else "-Xlint:all"
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf(lint, "-Werror"))
    }
}
