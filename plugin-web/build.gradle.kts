// The web game host every web core compiles in (GameServer, LocalStorageBridge, GameWebView):
// checked out with plugin-api/ by .github/actions/plugin-api and built into the core's own dex.
// Enginehost itself never loads it; this module exists so Enginehost's CI compiles and tests it.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.enginehost.web"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // GameServer's class refers to android.webkit types; the tests call only its plain parts.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    compileOnly(project(":plugin-api"))
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
