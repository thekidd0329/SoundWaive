import java.util.Properties

plugins { id("com.android.application") }

val releaseKeys =
    listOf(
        "SOUNDWAIVE_KEYSTORE",
        "SOUNDWAIVE_STORE_PASSWORD",
        "SOUNDWAIVE_KEY_ALIAS",
        "SOUNDWAIVE_KEY_PASSWORD",
    )
val signingValues = releaseKeys.associateWith { providers.environmentVariable(it).orNull }

require(
    signingValues.values.all { it == null } || signingValues.values.all { !it.isNullOrBlank() }
) {
    "Release signing needs all four SOUNDWAIVE signing environment variables. See docs/BUILDING.md."
}

val hasReleaseSigning = signingValues.values.all { !it.isNullOrBlank() }

android {
    namespace = "com.parker.crownpulse"
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        // Keep the existing identity so this is an update to the original private build.
        applicationId = "com.parker.crownpulse"
        // Pixel-only build: LiteRT's NPU (Tensor TPU) runtime needs Android 12 and arm64.
        minSdk = 31
        targetSdk = 35
        versionCode = 16
        versionName = "1.13.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rust-jni").get().asFile)
    // Extracted native libraries: LiteRT finds the Tensor dispatch library on disk.
    packaging { jniLibs.useLegacyPackaging = true }
    // Models are memory-mapped from the APK, so keep them uncompressed.
    androidResources { noCompress += "tflite" }
    signingConfigs {
        if (hasReleaseSigning)
            create("privateRelease") {
                storeFile = file(signingValues.getValue("SOUNDWAIVE_KEYSTORE")!!)
                storePassword = signingValues.getValue("SOUNDWAIVE_STORE_PASSWORD")
                keyAlias = signingValues.getValue("SOUNDWAIVE_KEY_ALIAS")
                keyPassword = signingValues.getValue("SOUNDWAIVE_KEY_PASSWORD")
            }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("privateRelease")
        }
    }
    lint { abortOnError = true }
}

// LiteRT, its GPU accelerator and the Google Tensor NPU dispatch library. The three must be
// the same version, and an AOT-compiled *_Google_Tensor_G5.tflite must be compiled for it
// (see docs/AI-UPSCALE.md).
val litert = "2.3.0"

dependencies {
    implementation("com.google.ai.edge.litert:litert:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu:$litert")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-google-tensor:$litert")
    testImplementation("junit:junit:4.13.2")
}

// LiteRT 2.3.0's Kotlin API is compiled with metadata 2.4. AGP 9.4 ships Kotlin 2.2,
// which can only read metadata up to 2.3, so skip that check (same flag as Google's
// LiteRT samples).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions { freeCompilerArgs.add("-Xskip-metadata-version-check") }
}

val nativeEngine =
    tasks.register("buildNativeEngine") {
        inputs.dir(rootProject.file("engine/src"))
        inputs.files(
            rootProject.file("engine/Cargo.toml"),
            rootProject.file("engine/Cargo.lock"),
            rootProject.file("rust-toolchain.toml"),
        )
        inputs.property("ndkVersion", "28.2.13676358")
        outputs.dir(layout.buildDirectory.dir("rust-jni"))
        doLast {
            val local =
                Properties().apply {
                    val p = rootProject.file("local.properties")
                    if (p.exists()) p.inputStream().use { load(it) }
                }
            val sdk =
                System.getenv("ANDROID_SDK_ROOT")
                    ?: System.getenv("ANDROID_HOME")
                    ?: local.getProperty("sdk.dir")
                    ?: error("Set sdk.dir in local.properties or ANDROID_SDK_ROOT")
            val host =
                when {
                    System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
                    System.getProperty("os.name").lowercase().contains("windows") ->
                        "windows-x86_64"
                    else -> "linux-x86_64"
                }
            val ndk = file("$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/$host/bin")
            require(ndk.isDirectory) { "Install Android NDK 28.2.13676358; expected $ndk" }
            val targetDir = rootProject.layout.buildDirectory.dir("rust-target").get().asFile
            val targets =
                // arm64 only (Pixel-only build, see abiFilters).
                mapOf("aarch64-linux-android" to "arm64-v8a")
            for ((target, abi) in targets) {
                val clangTarget =
                    if (target == "armv7-linux-androideabi") "armv7a-linux-androideabi" else target
                val linker =
                    File(
                        ndk,
                        "${clangTarget}26-clang" + if (host.startsWith("windows")) ".cmd" else "",
                    )
                require(linker.isFile) { "Android linker not found: $linker" }
                val process =
                    ProcessBuilder(
                            "cargo",
                            "build",
                            "--locked",
                            "--release",
                            "--lib",
                            "--target",
                            target,
                        )
                        .directory(rootProject.file("engine"))
                        .inheritIO()
                process.environment()["CARGO_TARGET_DIR"] = targetDir.absolutePath
                process
                    .environment()["CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER"] =
                    linker.absolutePath
                process.environment().remove("CARGO_ENCODED_RUSTFLAGS")
                process.environment()["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
                check(process.start().waitFor() == 0) {
                    "Rust build failed for $abi; see docs/BUILDING.md"
                }
                copy {
                    from(File(targetDir, "$target/release/libcrownpulse_engine.so"))
                    into(layout.buildDirectory.dir("rust-jni/$abi"))
                }
            }
        }
    }

tasks.named("preBuild").configure {
    dependsOn(nativeEngine, rootProject.tasks.named("verifyAssets"))
}
