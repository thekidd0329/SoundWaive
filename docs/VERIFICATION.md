# Verification

SoundWaive is tested on device by Christian, in real time, on his Pixel (Tensor G5). Every build from 1.0 through 1.11 was tested that way and passed. Issues he reported were fixed in later builds, as noted below. Builds he has not tested yet are marked awaiting his device test.

CI results are recorded separately. They are not a substitute for a device test.

## 1.0 through 1.11

| Version | Date | Device test | Notes |
| --- | --- | --- | --- |
| 1.0.0 | 2026-11-13 | Tested on device by Christian in real time; passed | Original CrownPulse build. |
| 1.0.1 | 2026-11-13 | Tested on device by Christian in real time; passed | SoundWaive repository integration. |
| 1.1.0 | 2026-11-17 | Tested on device by Christian in real time; passed | Beat grid and onset timing. |
| 1.2.0 | 2026-11-17 | Tested on device by Christian in real time; passed | Photo, GIF and video backgrounds. |
| 1.3.0 | 2026-11-18 | Tested on device by Christian in real time; passed | First pass at the half-tempo BPM bug (a 140 BPM track could read as about 70, or 68.3 on a short preview). Remaining straight-140 case fixed in 1.3.1. Also added a faster GPU export path. |
| 1.3.1 | 2026-11-18 | Tested on device by Christian in real time; passed | Fixed the half-tempo BPM bug: straight 140 BPM songs with a strong 1 and 3 no longer read as 70. |
| 1.4.0 | 2026-11-18 | Tested on device by Christian in real time; passed | Beat effects (rings, punch-in, snare color split). Looks saved before 1.4 picked up the new effects; that compatibility issue was fixed in 1.4.1. |
| 1.4.1 | 2026-11-18 | Tested on device by Christian in real time; passed | Old-looks compatibility fix: looks saved before 1.4 open with the beat effects off, as they were made. Includes 1.3.1. |
| 1.5.0 | 2026-11-18 | Tested on device by Christian in real time; passed | Note color glow and hi-hat sparkles. |
| 1.6.0 | 2026-11-18 | Tested on device by Christian in real time; passed | Ripple on hits and rainbow edge. |
| 1.7.0 | 2026-11-18 | Tested on device by Christian in real time; passed | Flash on drops and light rays. |
| 1.8.0 | 2026-11-18 | Tested on device by Christian in real time; passed | Build-up before drops and tilt on beats. |
| 1.9.0 | 2026-11-18 | Tested on device by Christian in real time; passed | Bounce on beats and orbit dots. |
| 1.10.0 | 2026-11-18 | Tested on device by Christian in real time; passed | Sound blob and star warp. |
| 1.11.0 | 2026-11-18 | Tested on device by Christian in real time; passed | Sound wave line and ring. |

### Issues found in device testing

- **Half-tempo BPM**, fixed in 1.3.1. A 140 BPM track could read as about 70, or 68.3 on a short preview. 1.3.0 added an octave check so half-time grooves and the short preview no longer landed on the half tempo. 1.3.1 finished the fix: straight 140 BPM songs with a strong 1 and 3 no longer read as 70.
- **Old-looks compatibility**, fixed in 1.4.1. Looks saved before 1.4 picked up the new beat effects (rings, punch-in and snare color split). 1.4.1 keeps those looks as they were made, with the new effects off.

Everything else Christian tested on 1.0 through 1.11 worked smoothly.

## 1.12.x through 1.17.5

These builds are awaiting Christian's device test.

| Version | Date | Device test | Notes |
| --- | --- | --- | --- |
| 1.12.0 | 2026-11-19 | Awaiting Christian's device test | GPU effects pass and zero-copy video backgrounds. The 1.12.0 GitHub Actions run failed while downloading the Gradle classpath (a Maven flake). |
| 1.12.1 | 2026-11-21 | Awaiting Christian's device test | Retrigger of the 1.12 work; no app change. CI green. |
| 1.13.0 | 2026-11-19 | Awaiting Christian's device test | AI background sharpening (LiteRT). The 1.13.0 GitHub Actions run failed to compile because LiteRT 2.3.0's Kotlin metadata is newer than the bundled compiler. |
| 1.13.1 | 2026-11-21 | Awaiting Christian's device test | Adds `-Xskip-metadata-version-check` so that LiteRT compile can proceed, and includes 1.12.1. Not yet compiled, because of a CI download flake. |
| 1.14.0 | 2026-11-21 | Awaiting Christian's device test | Background export: a `mediaProcessing` foreground service so a render keeps going when the app leaves the screen. |
| 1.15.0 | 2026-11-26 | Awaiting Christian's device test | Release Pack: one tap renders YouTube 16:9 (4K or 1080p), three distinct 15 s verticals, a 30 s reel, a square promo and a silent 8 s looping Spotify Canvas to Movies/SoundWaive/<song>/. Not built or run locally; awaiting CI and Christian's on-device test. |
| 1.16.0 | 2026-11-22 | Awaiting Christian's device test | Android 8+ support across four ARM and Intel ABIs. |
| 1.17.5 | 2026-11-27 | Awaiting Christian's device test | Manual Export › Clear cache only, on an allowlist of temp dirs; never deletes finished MP4s. Automatic launch/export sweeps removed after they deleted videos. Not built or run locally. |

GitHub Actions history for a given commit is at the [Android workflow runs](https://github.com/thekidd0329/SoundWaive/actions/workflows/android.yml).

## 1.16.0 compatibility checks

Before the Release Pack integration, the compatibility branch's local build passed 24 Rust tests, 53 JVM tests and Android lint with zero errors. The debug APK, release APK and release AAB contain the Rust analysis engine and the appropriate offline sharpening libraries for all four ABIs. An API 26 x86 emulator imported the demo, exported an H.264/AAC MP4 after leaving the activity and checked the AAC timing against a reference pulse.

The Android workflow runs the background export, AAC priming and photo sharpening tests on AOSP images for APIs 26, 34 and 35. Each device job publishes its instrumentation results. The rebased build also checks saving a completed MP4 through the Release Pack storage path and rendering a silent loop through the adaptive AVC encoder. These new combined checks, the AVC/AAC fallback changes and the rebased build are awaiting CI; earlier results are historical.

## Original 1.0 emulator checks

Before on-device testing, the original CrownPulse 1.0 build was also checked on an Android 11 emulator on 13 November 2026, using the full independent `losingmymindvlc.flac` (261.47 seconds, stereo, 44.1 kHz). That private test song is not committed.

The detailed emulator report and numerical export measurements are in [ORIGINAL-VERIFICATION.md](ORIGINAL-VERIFICATION.md) and [export-verification.json](export-verification.json). Those figures are from the original emulator checks, not from later on-device tests, and they are not a claim that every flow was rerun for the SoundWaive rename.

Beat and bass pitches remain approximate animation inputs, not music transcription.
