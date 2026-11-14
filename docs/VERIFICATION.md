# Verification

## SoundWaive repository build — November 13, 2026

The repository was built locally on Linux with JDK 17, the checked-in Gradle 9.6.1 wrapper, AGP 9.4.0, Android SDK 35, build tools 36.0.0, NDK 28.2.13676358 and Rust 1.100.0.

Command: `./gradlew --no-daemon check :app:assembleDebug :app:assembleRelease :app:bundleRelease`

- **Build passed**, including source compilation of ARM64, ARMv7 and x86_64 Rust libraries.
- **4 Rust tests passed**: FFT frequency, finite silence normalization, pulse-train tempo and complete analysis generation.
- **4 JVM tests passed**: AAC edit timing and unchanged media offsets, version-1 MP4 duration headers, safe rejection of a missing audio track, and safe rejection of truncated metadata.
- **Android lint: 0 errors, 9 warnings.** Remaining warnings cover newer tool/SDK versions, Android backup-rule modernization, conservative disk-space checks and localization. These are recorded rather than hidden with a blanket baseline.
- Bundled generated demo audio, regenerated analysis and artwork passed SHA-256 integrity checks. The unreleased recording and its original analysis are excluded from the committed tree and build artifacts. Generated demo media are also excluded from Git; the build recreates them from the included script.
- Development APK, unsigned release APK and unsigned Android App Bundle were generated. A separate signed release build also passed. Its certificate matches the original CrownPulse install, with the same application ID and a higher version code; the package label is SoundWaive.
- The repository preserves the initial GitHub commit. Private signing keys, SDKs, dependency caches, generated builds and unrelated test music are excluded from version control.

The initial strict lint run identified an API-27 theme attribute in an API-26 resource and an extractor/codec flag mismatch. The theme now uses a version-qualified override, and the audio mux path maps those flags explicitly. Checks passed after both fixes.

The GitHub workflow repeats the same build/check command on Ubuntu and publishes packages and reports. Consult the [workflow runs](https://github.com/thekidd0329/SoundWaive/actions/workflows/android.yml) for the status of a specific commit; a local pass is not a claim about a future remote run.

## Original visualizer and audio validation

The implementation was previously checked in an Android 11 emulator using the full independent `losingmymindvlc.flac` from the owner's Music/flac folder: 261.47 seconds, stereo, 44.1 kHz. That private test song is not committed.

Analysis, all eight preset renders, custom artwork decoding/storage, playback controls, saved looks, locked randomization and export controls were exercised. Short portrait and landscape exports decoded correctly. Corrected AAC audio measured zero sample offset against the source; native Android re-import retained the exact audible length. A separate 48 kHz mono fixture also passed.

See [the original detailed report](ORIGINAL-VERIFICATION.md) and [numerical export measurements](export-verification.json). These record the original CrownPulse build and remain historical evidence, not a claim that every flow was rerun for the SoundWaive rename.

## Still to validate

- Physical-phone performance, thermal behavior, memory and vendor codec combinations.
- A new full-length export of the independent test song. The full song was analyzed; emulator exports were short sections.
- Complete custom-image selection through the emulator's system picker. The picker opened/listed images and the import handler passed independently, but the combined picker interaction was inconclusive.
- New Android target-SDK migrations and broader distribution requirements.

Beat and bass pitches remain approximate animation inputs, not verified music transcription. The native design closely follows the approved look; individual renderer details can differ from the original desktop video.
