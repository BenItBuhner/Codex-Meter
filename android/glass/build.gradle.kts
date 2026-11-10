// The Liquid Glass design: Codex Meter's screens built from Glassé's kit (the same components and layout
// code as Glassé's own demo app). Pure Java over Skia: the app supplies skiko-android and the data; the
// JVM tests render the screens offscreen next to Glassé's demo screens.
plugins {
    `java-library`
}

val glasse = "f1b84bcf24"
val skiko = "0.156.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("com.github.BenItBuhner.Glasse:glasskit:$glasse")
    compileOnly("org.jetbrains.skiko:skiko-awt:$skiko")
    testImplementation("org.jetbrains.skiko:skiko-awt:$skiko")
    testImplementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:$skiko")
    testImplementation("com.github.BenItBuhner.Glasse:glasse-demo-kt:$glasse")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    maxHeapSize = "4g"
    // Screenshots run only when asked for: ./gradlew :glass:test -Dcap.out=<dir>
    System.getProperties().forEach { (k, v) ->
        if (k.toString().startsWith("cap")) systemProperty(k.toString(), v)
    }
}
