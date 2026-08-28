plugins { id("com.android.application") }
android {
    namespace = "dev.enginehost.plugin.cmvs"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.enginehost.plugin.cmvs.slot1"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
