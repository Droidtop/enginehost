plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.enginehost"
    compileSdk = 36

    // CI passes the workflow run number: a plain monotonic integer, so
    // Android itself can answer "is this newer" (and refuse downgrades),
    // and the in-app update check compares the same number. Local builds
    // fall back to 1 and a versionName that says so.
    val versionRevision = (System.getenv("VERSION_REVISION")?.toIntOrNull() ?: 0).coerceAtLeast(0)

    defaultConfig {
        applicationId = "dev.enginehost"
        minSdk = 26
        targetSdk = 34
        versionCode = if (versionRevision > 0) versionRevision else 1
        versionName = if (versionRevision > 0) "0.1.0-dev-$versionRevision" else "0.1.0-local"
    }

    val ciKeystore = System.getenv("ENGINEHOST_ANDROID_KEYSTORE")
    if (!ciKeystore.isNullOrBlank()) {
        signingConfigs {
            create("enginehostCi") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("ENGINEHOST_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ENGINEHOST_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ENGINEHOST_ANDROID_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("enginehostCi")
            }
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

dependencies {
    implementation(project(":plugin-api"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
