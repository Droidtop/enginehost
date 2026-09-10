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
        val baseVersion = providers.gradleProperty("enginehost.version").get()
        versionName = if (versionRevision > 0) "$baseVersion-dev-$versionRevision" else "$baseVersion-local"
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

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/platformDatabase/assets"))
}

// --- The bundled engine registry is a SNAPSHOT of droidtop-platforms -----
// engines-database.json is the ONE classification authority for enginehost
// and droidtop alike (droidtop's docs/SPEC.md 7e2b), so a copy of it living
// by hand in each repo's source tree is exactly the drift that rule exists
// to prevent -- and it had already drifted. vendor/droidtop-platforms is a
// submodule pinned to one commit; this task copies that commit's registry
// into a generated assets directory at build time and writes the commit
// beside it, so a build ships one identifiable state of the platform repo
// and can say which. Both apps pin their own commit; the runtime refresh is
// what keeps them level between releases.
val platformsRepoDir = rootProject.file("vendor/droidtop-platforms")

val platformDatabaseSeedFiles = listOf("engines-database.json")

/** The pinned submodule commit, or "unknown" when git cannot say (a source-zip build). */
val platformDatabaseSnapshotCommit: String = runCatching {
    providers.exec {
        commandLine("git", "-C", platformsRepoDir.absolutePath, "rev-parse", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrDefault("").ifEmpty { "unknown" }

val platformDatabaseSeedDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/platformDatabase/assets")

val platformDatabaseSeed = tasks.register<Copy>("platformDatabaseSeed") {
    description = "Copies the pinned droidtop-platforms snapshot into the bundled assets."
    from(platformsRepoDir) { include(platformDatabaseSeedFiles) }
    into(platformDatabaseSeedDir)
    inputs.property("snapshotCommit", platformDatabaseSnapshotCommit)
    doFirst {
        val missing = platformDatabaseSeedFiles.filterNot { File(platformsRepoDir, it).isFile }
        check(missing.isEmpty()) {
            "vendor/droidtop-platforms has no $missing -- run " +
                "`git submodule update --init vendor/droidtop-platforms`"
        }
    }
    doLast {
        platformDatabaseSeedDir.get().file("platform-database-snapshot.json").asFile
            .writeText("""{"commit": "$platformDatabaseSnapshotCommit"}""")
    }
}

tasks.named("preBuild") { dependsOn(platformDatabaseSeed) }
// The detector/scanner tests parse the SHIPPED seed, which is now generated.
tasks.withType<Test>().configureEach { dependsOn(platformDatabaseSeed) }

dependencies {
    implementation(project(":plugin-api"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
