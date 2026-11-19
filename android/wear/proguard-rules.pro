# R8 rules for the release watch app (see buildTypes.release in build.gradle.kts). Wear, Tiles,
# ProtoLayout, Guava and Play services bring their own rules; aapt2 keeps the manifest's services.

# Stack traces in diagnostics stay readable: the app's own classes and members keep their names.
-keepnames class dev.bennett.codexmeter.** { *; }
-keepattributes SourceFile,LineNumberTable
