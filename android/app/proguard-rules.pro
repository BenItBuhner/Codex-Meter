# R8 rules for the release phone app (see buildTypes.release in build.gradle.kts).
#
# Libraries bring their own rules: Glassé's glasse-android keeps skiko, Skia and its kit (their
# native code looks classes up by name), and AndroidX, SESL and Play services keep what they reach by
# reflection. Classes named in the manifest and in layouts are kept by the rules aapt2 writes.

# Stack traces in diagnostics and crash reports stay readable: the app's own classes and members
# keep their names (R8 still removes and inlines unused code), with file names and line numbers.
-keepnames class dev.bennett.codexmeter.** { *; }
-keepattributes SourceFile,LineNumberTable

# oneui-design is built for SESL8. Its immersive-scroll helper names a SESL8 class that SESL9 dropped;
# the One UI 9 chrome never takes that path, and without R8 the reference was just as unresolved.
-dontwarn com.google.android.material.appbar.SeslImmersiveScrollBehavior

# ML Kit (the transfer screen's QR scanner) finds its components through Firebase's
# ComponentDiscovery, which builds each registrar named in the manifest by reflection. ML Kit
# ships no rule for it, and without one R8 drops their constructors (seen in ci/shrunk-smoke.sh).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
