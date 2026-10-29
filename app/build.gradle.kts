import java.net.URI
import java.security.MessageDigest

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
        // 64-bit only, the owner's rule (Droidtop/tracker#646): the same two ABIs every core ships.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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
        // Both build types carry the one persistent key, so either installs
        // over the other: the published download is the release build, and
        // the debug build (with its adb install harness, src/debug) is
        // published beside it for test rigs.
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("enginehostCi")
            }
            getByName("release") {
                signingConfig = signingConfigs.getByName("enginehostCi")
            }
        }
    }

    // The :runtime process's network filter and lockdown (RuntimeSandbox,
    // docs/engine-sandbox.md), for the two ABIs in abiFilters.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // The isolated runtime's Binder contract (docs/engine-sandbox.md "Layer
    // 2"): IEngineFileBroker, IEngineRuntimeCallback, IEngineRuntimeService
    // under app/src/main/aidl. AGP 8 no longer compiles .aidl by default.
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/platformDatabase/assets"))

    // The version and trust code droidtop and Enginehost share (vendor/droidtop-shared, Droidtop/droidtop-shared, pinned as a
    // submodule): compiled in as source, so neither app depends on the other and no Kotlin plugin version is shared
    // (docs/engine-bundle-format.md "Shared trust code").
    sourceSets.getByName("main").java.srcDir("../vendor/droidtop-shared/version/src/main/kotlin")
    sourceSets.getByName("main").java.srcDir("../vendor/droidtop-shared/trust/src/main/kotlin")
    // The names droidtop and Enginehost agree on to start each other (docs/contract.md, Droidtop/tracker#204).
    sourceSets.getByName("main").java.srcDir("../vendor/droidtop-shared/launch-contract/src/main/kotlin")
    // One GitHub client for both apps: which requests may carry the token, redirects decided per hop, releases, downloads
    // (Droidtop/tracker#194, #195; GithubClients).
    sourceSets.getByName("main").java.srcDir("../vendor/droidtop-shared/net/src/main/kotlin")
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

// --- On-device voices: sherpa-onnx, in the APK (owner, 2026-10-29) -------
// "I think the engine should still be in the build, that way they can supply
// their own voices" (Droidtop/tracker#490): the engine ships, the voice models
// are downloads or the person's own files. The AAR is k2-fsa's own release,
// pinned by SHA-256 (the same pin as droidtop's keyboard, #343), fetched once
// into Gradle's cache and refused if it differs. Nothing large is committed.
val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaOnnxAar = File(gradle.gradleUserHomeDir, "caches/enginehost/sherpa-onnx-$sherpaOnnxVersion.aar")

val sherpaOnnxFetch = tasks.register("sherpaOnnxFetch") {
    description = "Fetches the pinned sherpa-onnx AAR and checks its SHA-256."
    outputs.file(sherpaOnnxAar)
    inputs.property("sha256", sherpaOnnxSha256)
    doLast {
        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (sherpaOnnxAar.isFile && sha256(sherpaOnnxAar) == sherpaOnnxSha256) return@doLast
        sherpaOnnxAar.parentFile.mkdirs()
        val partial = File(sherpaOnnxAar.path + ".part")
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
        URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        val got = sha256(partial)
        check(got == sherpaOnnxSha256) {
            partial.delete()
            "sherpa-onnx-$sherpaOnnxVersion.aar has SHA-256 $got, not the pinned $sherpaOnnxSha256"
        }
        check(partial.renameTo(sherpaOnnxAar)) { "Could not move the sherpa-onnx AAR into place" }
    }
}
tasks.named("preBuild") { dependsOn(sherpaOnnxFetch) }

android {
    packaging {
        jniLibs {
            // The JNI library needs only libonnxruntime.so; the C and C++ API libraries are unused.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

dependencies {
    implementation(files(sherpaOnnxAar).builtBy(sherpaOnnxFetch))
    implementation(project(":plugin-api"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    // The shared verifier reads a signature's JWS header with it (a library only: no serialization plugin or generated code).
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.bouncycastle.pkix)
    testImplementation(libs.conscrypt.openjdk)
}
