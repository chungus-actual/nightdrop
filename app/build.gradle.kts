// Nightdrop: a music visualizer and screensaver for Android TV. No third-party libraries: the Android
// SDK, Kotlin's standard library and OpenGL ES 3.
//
// Build switches (all optional):
//   -PappId=<id>      the applicationId (default io.github.chungus_actual.nightdrop)
//   -Pbake=<dir>      a folder holding nightdrop.json (and the CA file it names) to build in as the
//                     default config; a nightdrop.json pushed to the device still wins (Config.kt)
//   KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD (environment)
//                     sign the release build with that key; without them it is signed with the debug key
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.chungus_actual.nightdrop"
    compileSdk = 34

    defaultConfig {
        applicationId = (project.findProperty("appId") as String?) ?: "io.github.chungus_actual.nightdrop"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "0.1.1"
    }

    (project.findProperty("bake") as String?)?.let { dir ->
        val baked = rootProject.file(dir)
        require(baked.resolve("nightdrop.json").isFile) { "-Pbake: no nightdrop.json in $baked" }
        sourceSets.getByName("main").assets.srcDir(baked)
    }

    val keystore = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
