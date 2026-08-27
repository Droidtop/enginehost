plugins { id("com.android.application") }

android {
    namespace = "dev.enginehost.plugin.godot"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.enginehost.plugin.godot.slot1"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.gitignore:!.ds_store:!*.scc:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

dependencies { implementation("org.godotengine:godot:4.7.1.stable") }
