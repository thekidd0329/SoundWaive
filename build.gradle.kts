plugins {
    base
    id("com.android.application") version "9.4.0" apply false
}

val rustTest by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Run the Rust signal-processing tests."
        workingDir("engine")
        environment(
            "CARGO_TARGET_DIR",
            layout.buildDirectory.dir("rust-tests").get().asFile.absolutePath,
        )
        commandLine("cargo", "test", "--locked")
    }

val rustFmt by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Check Rust source formatting."
        workingDir("engine")
        commandLine("cargo", "fmt", "--all", "--", "--check")
    }

val generateDemo by tasks.registering(Exec::class) {
    group = "build"
    description = "Generate the bundled demo from oscillators and noise; no recordings are used."
    inputs.file("tools/generate_demo.py")
    inputs.dir("engine/src")
    inputs.files("engine/Cargo.toml", "engine/Cargo.lock", "rust-toolchain.toml")
    outputs.files("app/src/main/assets/generated-demo.m4a", "app/src/main/assets/generated-demo.analysis", "generated-demo.sha256", "docs/generated-demo.json")
    commandLine("python3", "tools/generate_demo.py")
}

val verifyAssets by tasks.registering {
    dependsOn(generateDemo)
    group = "verification"
    description = "Check bundled artwork, music, and analysis against their recorded hashes."
    inputs.files("assets.sha256", "generated-demo.sha256")
    inputs.dir("app/src/main/assets")
    doLast {
        (file("assets.sha256").readLines() + file("generated-demo.sha256").readLines())
            .filter { it.isNotBlank() }
            .forEach { line ->
                val (expected, path) = line.split("  ", limit = 2)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file(path).inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        digest.update(buffer, 0, size)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expected) { "Bundled asset failed its integrity check: $path" }
            }
    }
}

tasks.named("check") {
    dependsOn(rustTest, rustFmt, verifyAssets, ":app:testDebugUnitTest", ":app:lintDebug")
}
