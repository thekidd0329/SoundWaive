# CrownPulse 1.0 — verification

Built and checked on November 13, 2026.

## What was checked

- The Kotlin/Rust project builds successfully with Gradle 9.6.1, AGP 9.4.0, SDK 35 and NDK 28.2.13676358.
- All four Rust tests pass: FFT frequency detection, finite silence normalization, pulse-train tempo estimation and complete analysis-file generation.
- Android 11 x86_64 emulator: decoded the complete **losingmymindvlc.flac**, selected from the user's Music/flac folder. Duration: **261.47 seconds**, stereo, 44.1 kHz. The Rust engine estimated **140 BPM**, **869 bass-note events** and **633 transients**. These are estimates for animation.
- The original demo and all eight presets rendered on Android. The original crown preset was compared visually against the approved video at the same timestamp. The final tint preserves its distressed texture; white and cyan renders were inspected.
- Android exported a **2-second portrait clip at 30 fps** (60 frames) and a **1-second landscape clip at 24 fps** (24 frames), using two different portions of the new track. Both files decode without errors. Portrait display size is 1080×1920; the emulator's landscape-only encoder stores 1920×1080 plus an upright-display rotation tag. Landscape is 1280×720.
- A waveform comparison found the platform AAC encoder's 2048-sample priming delay. The app now pads the end and writes an MP4 edit list to preserve the full audible range and align it with the video. Corrected portrait and landscape audio measured **0 samples of offset** against the source, with correlations of **0.99986** and **0.99957**. Their audio and video durations match.
- A separate **48 kHz mono** fixture measured **0 samples of offset**, with **0.99940** correlation. AAC is lossy; these figures assess timing and correspondence, not bit-identical audio.
- UI checks exercised playback and pause, symbol/color selection, locked randomization, saved-look loading, export shape/resolution/frame-rate selection and the Android export file picker.
- Custom artwork passed a direct test of the same import handler used by the Android picker: PNG decoding, app-local persistence, and selecting Custom artwork all succeeded. The system picker opened and listed files, but completing selection through that emulator UI was inconclusive.
- The release APK excludes test instrumentation, has a valid v2/v3 signature, passes ZIP integrity/alignment checks, and contains ARM64, ARMv7 and x86_64 native libraries. Native libraries use 16 KB load alignment. It requests no Internet permission.

## Limits of this verification

A physical Android phone was not available. Device-specific performance, thermal behavior, codec support and every Android version have not been measured. The emulator used software CPU emulation, so its speed is not representative of a phone.

The entire independent song was decoded and analyzed; test exports were short sections. A new full-length export of that song was not run. The app supports full-track export and includes the complete original demo.

Bass notation is approximate, not verified transcription. The visual design is a native port of the approved look; renderer details and particle placement can differ from the original desktop video.

Native Android re-import of the corrected portrait MP4 also passed: exactly 88200 stereo PCM frames (2 seconds), 0 samples offset and 0.99986 source correlation.

The final signed release APK was installed and launched successfully. The tab-scroll correction and artwork file picker were verified in that release.
