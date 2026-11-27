# Building SoundWaive

## Pinned toolchain

| Component | Version |
| --- | --- |
| JDK | 17 |
| Gradle wrapper | 9.6.1, SHA-256 verified |
| Android Gradle Plugin | 9.4.0, built-in Kotlin |
| Android compile/target SDK | 35 |
| Android minimum SDK | 26 (Android 8.0) |
| Packaged ABIs | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` |
| Android build tools | 36.0.0 |
| Android NDK | 28.2.13676358 |
| Rust | 1.100.0, recorded in rust-toolchain.toml |

Linux is the locally verified build host. macOS and Windows linker paths are selected automatically but have not been exercised here. On Windows, use `gradlew.bat`. Install the matching tools through Android Studio or `sdkmanager`, plus Python 3 and FFmpeg; ensure `java`, `cargo`, `rustup`, `python3`, and `ffmpeg` are on PATH.

```sh
sdkmanager 'platforms;android-35' 'build-tools;36.0.0' 'ndk;28.2.13676358'
```

Accept Android SDK licenses if prompted. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to the SDK directory, or add `sdk.dir=/your/sdk/path` to an untracked `local.properties`. If both variables exist, point them to the same SDK.

```sh
./gradlew --no-daemon check :app:assembleDebug
```

`check` runs the Rust and JVM regression suites, Rust formatting verification, Android lint, and bundled-asset SHA-256 checks. Native libraries are compiled from source before Android packaging. Cargo uses `--locked` and the committed lockfile.

Outputs:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Unsigned release bundle: `app/build/outputs/bundle/release/app-release.aab`
- JVM results: `app/build/reports/tests/testDebugUnitTest/index.html`
- Android lint: `app/build/reports/lint-results-debug.html`

```sh
./gradlew :app:assembleRelease :app:bundleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds use `com.parker.crownpulse.debug`. Release builds retain `com.parker.crownpulse`; version 1.0.1/code 2 can update the original version 1.0/code 1 when signed with the same certificate.

## Release signing

The default release build is unsigned. To produce a signed release, provide **all four** environment variables through your local secret manager or shell environment:

| Variable | Value |
| --- | --- |
| `SOUNDWAIVE_KEYSTORE` | Absolute path to your private keystore |
| `SOUNDWAIVE_STORE_PASSWORD` | Keystore password |
| `SOUNDWAIVE_KEY_ALIAS` | Signing key alias |
| `SOUNDWAIVE_KEY_PASSWORD` | Signing key password |

Then run `./gradlew :app:assembleRelease :app:bundleRelease`. The signed APK is `app/build/outputs/apk/release/app-release.apk`. Partial signing configuration is rejected. Do not add passwords or keystores to tracked files, workflow YAML, logs or build artifacts.

### Published releases

Signed releases are built by `.github/workflows/release.yml` when a `v*` tag is pushed (or by running it manually with an existing tag). The release key (`soundwaive-release`, RSA 4096, created for v1.14.0) lives only in the repository secrets `SOUNDWAIVE_KEYSTORE_BASE64`, `SOUNDWAIVE_STORE_PASSWORD`, `SOUNDWAIVE_KEY_ALIAS` and `SOUNDWAIVE_KEY_PASSWORD`; the owner keeps an offline backup of the keystore and its passwords. Every future release must be signed with this same key so updates install over each other. Builds signed with the earlier local key cannot be updated by this key, so uninstall that install once before installing a v1.14.0+ release. The workflow verifies the APK signature with `apksigner`, publishes a GitHub prerelease with `SoundWaive-<tag>-beta.apk`, `.aab` and `SHA256SUMS.txt`, and deletes the decoded keystore from the runner. The regular `android.yml` workflow still has no access to the key and produces the development build plus unsigned release packages.

Reference: [Android app signing](https://developer.android.com/studio/publish/app-signing), [Gradle wrapper verification](https://docs.gradle.org/current/userguide/gradle_wrapper.html#sec:verification).

## Development

```sh
cargo fmt --all --manifest-path engine/Cargo.toml
cargo test --locked --manifest-path engine/Cargo.toml
./gradlew check
```

After deliberately changing bundled artwork, update its line in `assets.sha256` with the new SHA-256 and review that change alongside the asset. The build rejects missing or corrupted media. Build directories, local SDK settings, generated packages and signing files are ignored by Git.

With an Android device or emulator attached, run `./gradlew :app:connectedDebugAndroidTest` to exercise offline sharpening, AAC timing, and background MP4 export using bundled audio.

The older optional `qa/` instrumentation uses private emulator fixtures and is not shipped in release builds. Its original reproduction procedure is documented there. Hardware testing and representative full-song exports are still needed before broader distribution.

## Generated demo

The bundled 32-second demo uses only mathematical oscillators and seeded noise. No owner recording is included. `tools/generate_demo.py` records its provenance and regenerates the audio, analysis and `generated-demo.sha256`. Regeneration requires Python 3, FFmpeg and Cargo; Gradle generates these ignored assets before verifying and packaging them, so fresh builds require those tools. The app itself includes no Python or FFmpeg runtime. Run `python3 tools/generate_demo.py` from the repository root.
