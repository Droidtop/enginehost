plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.enginehost"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.enginehost"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
