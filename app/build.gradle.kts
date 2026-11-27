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

// Android ABI to Rust target. Packaging and cross-compilation must agree.
val androidTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86" to "i686-linux-android",
    "x86_64" to "x86_64-linux-android",
)
val minimumAndroidApi = 26
// ABIs whose engine is built with the `lyrics` cargo feature (tract ONNX). All four build on
// NDK 28.2.13676358; drop an ABI here and its engine builds with --no-default-features, so
// NativeEngine.lyricsAvailable() is false there and the app hides the Lyrics screen.
val lyricsAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

android {
    namespace = "com.parker.crownpulse"
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        // Keep the existing identity so this is an update to the original private build.
        applicationId = "com.parker.crownpulse"
        minSdk = minimumAndroidApi
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 21
        versionName = "1.17.0"
        ndk { abiFilters += androidTargets.keys }
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
// CompiledModel has no 32-bit Intel binary. Use only the x86 C runtime and headers
// from the Interpreter distribution so its older Java API never enters the app.
val x86Interpreter = configurations.create("x86Interpreter") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu:$litert")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-google-tensor:$litert")
    add(x86Interpreter.name, "com.google.ai.edge.litert:litert:1.4.2@aar")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
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
        inputs.dir(rootProject.file("engine/lyrics-aligner/src"))
        inputs.files(
            rootProject.file("engine/lyrics-aligner/Cargo.toml"),
            rootProject.file("engine/Cargo.toml"),
            rootProject.file("engine/Cargo.lock"),
            rootProject.file("rust-toolchain.toml"),
        )
        inputs.property("ndkVersion", "28.2.13676358")
        val x86Bridge = file("src/main/c/x86_upscale.c")
        inputs.files(x86Interpreter, x86Bridge)
        inputs.property("androidTargets", androidTargets)
        inputs.property("minimumAndroidApi", minimumAndroidApi)
        inputs.property("lyricsAbis", lyricsAbis)
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
            val cpuRuntime = File(temporaryDir, "x86-interpreter")
            copy {
                from(zipTree(x86Interpreter.singleFile))
                include("headers/**", "jni/x86/libtensorflowlite_jni.so")
                into(cpuRuntime)
            }
            for ((abi, target) in androidTargets) {
                val clangTarget =
                    if (target == "armv7-linux-androideabi") "armv7a-linux-androideabi" else target
                val linker =
                    File(
                        ndk,
                        "${clangTarget}${minimumAndroidApi}-clang" + if (host.startsWith("windows")) ".cmd" else "",
                    )
                require(linker.isFile) { "Android linker not found: $linker" }
                if (abi == "x86") {
                    val libDir = layout.buildDirectory.dir("rust-jni/x86").get().asFile
                    val runtime = File(cpuRuntime, "jni/x86/libtensorflowlite_jni.so")
                    libDir.mkdirs()
                    runtime.copyTo(File(libDir, runtime.name), overwrite = true)
                    val bridge = File(libDir, "libsoundwaive_x86_sharpen.so")
                    val compile = ProcessBuilder(
                        linker.absolutePath, "-std=c11", "-O2", "-fPIC", "-shared",
                        "-Wall", "-Wextra", "-Werror", "-Wl,--no-undefined",
                        "-Wl,-z,max-page-size=16384", "-I${cpuRuntime.absolutePath}/headers",
                        x86Bridge.absolutePath, runtime.absolutePath, "-landroid", "-o", bridge.absolutePath,
                    )
                    check(compile.inheritIO().start().waitFor() == 0) { "Failed to build x86 sharpening" }
                }
                val process =
                    ProcessBuilder(
                            "cargo",
                            "build",
                            "--locked",
                            "--release",
                            "--lib",
                            "--target",
                            target,
                            *(if (abi in lyricsAbis) emptyArray<String>() else arrayOf("--no-default-features")),
                        )
                        .directory(rootProject.file("engine"))
                        .inheritIO()
                process.environment()["CARGO_TARGET_DIR"] = targetDir.absolutePath
                process
                    .environment()["CARGO_TARGET_${target.uppercase().replace('-', '_')}_LINKER"] =
                    linker.absolutePath
                // tract's build script compiles its assembly kernels with the cc crate: point it
                // at the same NDK clang and archiver.
                val ccTarget = target.replace('-', '_')
                process.environment()["CC_$ccTarget"] = linker.absolutePath
                process.environment()["AR_$ccTarget"] =
                    File(ndk, "llvm-ar" + if (host.startsWith("windows")) ".exe" else "").absolutePath
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
