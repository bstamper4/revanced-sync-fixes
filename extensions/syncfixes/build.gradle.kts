extension {
    name = "extensions/syncfixes.rve"
}

android {
    namespace = "app.revanced.extension"

    // Sync's minSdk: these helpers run inside Sync.
    defaultConfig {
        minSdk = 23
    }

    lint {
        checkOnly += setOf("NewApi", "InlinedApi")
        abortOnError = true
    }
}

dependencies {
    // Both are provided by Sync itself at runtime; only their signatures are needed to compile.
    compileOnly(libs.volley)
    compileOnly(libs.okhttp)
}

// The extension plugin applies Kotlin, which puts kotlin-stdlib on the runtime classpath. This extension is
// pure Java, so keep it (and its 1000+ classes) out of the bundle.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
