# Verification

## 1.7.0 flash on drops and light rays on bass — November 18, 2026

- Nothing was compiled, tested or rendered on the build box; the change was checked by reading it against the existing sources and the Android API docs (`Path.reset`/`moveTo`/`lineTo`/`close` reuse one native path; `Canvas.drawPath` fills with the paint's color and alpha).
- New JVM tests to run (`./gradlew :app:testDebugUnitTest`): in `BeatFxTest`, `findsADropWhereTheMusicJumps`, `noDropsInSteadyOrEmptyMusic`, `dropFlashFadesOut`, `lightRaysFollowTheBass`; in `AnalysisFormatTest.legacyFileWithoutBeatGridStillLoads`, the drop arrays.
- Not yet checked: everything on a phone, in particular whether drops land where a listener hears them on real songs, preview and export frame time with all nine effects on, and that looks saved in 1.6 open with the flash and rays off.

## 1.6.0 ripple on hits and rainbow edge — November 18, 2026

- Nothing was compiled, tested or rendered on the build box; the change was checked by reading it against the existing sources and the Android API docs (`Canvas.drawBitmapMesh` takes `(meshWidth + 1) * (meshHeight + 1)` vertex pairs and draws with the paint's alpha and color filter; it is supported on hardware canvases from API 18, below this app's minSdk 26).
- New JVM tests to run (`./gradlew :app:testDebugUnitTest`): in `BeatFxTest`, `latestHitKeepsTheBiggerRipple`, `rippleIsADampedWaveBehindItsFront`, `centroidIsTheBalancePointOfTheBands`, `rainbowEdgeOnlyOnBrightSounds`; in `AnalysisFormatTest.legacyFileWithoutBeatGridStillLoads`, the onset arrays.
- Not yet checked: everything on a phone, in particular how the ripple reads on real songs, preview and export frame time with all seven effects on, and that looks saved in 1.5 open with the ripple and rainbow edge off.

## 1.5.0 note color glow and hi-hat sparkles — November 18, 2026

- Nothing was compiled, tested or rendered on the build box; the change was checked by reading it against the existing sources and the Android/Kotlin API docs (`Bitmap.extractAlpha` masks are drawn in the paint's color; `Color.HSVToColor`).
- New JVM tests to run (`./gradlew :app:testDebugUnitTest`): in `BeatFxTest`, `fifthsHueWalksTheCircleOfFifths`, `noteColorBlendsRecentNotes`, `glowIsBoundedAndFollowsBass`, `sparklesOnlyOnLoudHats`, `hashIsRepeatableAndInRange`; in `AnalysisFormatTest.legacyFileWithoutBeatGridStillLoads`, the note arrays.
- Not yet checked: everything on a phone, in particular how the glow color reads on real songs, frame time with all five effects on, and that looks saved in 1.4 open with the glow and sparkles off.

## 1.4.1 saved-look compatibility — November 18, 2026

- Nothing was compiled, tested or rendered on the build box; the change was checked by reading it. `Settings` uses `android.graphics.Color` and `org.json`, which the JVM unit tests do not load, so the look upgrade has no unit test; check it on a phone.
- Not yet checked: loading a look saved in 1.3 (effects off), a new look and each preset (effects on, Minimal pulse without color split), an imported 1.3 settings file, and the session restored after updating from 1.3.

## 1.4.0 beat effects — November 18, 2026

- Per the owner's rule, nothing was compiled, tested or rendered on the build box. The code was checked by reading it against the existing sources and the Android/Kotlin API documentation only.
- New JVM tests for the owner to run (`./gradlew :app:testDebugUnitTest`): `BeatFxTest` (beat lookup, beat/bar ages on the tracked and fixed grids, cubic fade, ease-out ring travel, bounded punch-in, snare color-split threshold) and new assertions in `AnalysisFormatTest.beatGridIsParsedAndCountsBars` / `legacyFileWithoutBeatGridStillLoads` for the primitive beat arrays.
- Not yet checked: everything on a phone, in particular that preview and export show the same rings/punch/split, frame time with all three effects on, and how the effects read on real songs with weak or missing downbeats.

## 1.3.1 tempo octave review fix — November 18, 2026

- Nothing was compiled, tested or rendered on the build box; the change was checked by reading it.
- New Rust tests to run (`cd engine && cargo test`): `straight_quarters_at_70_are_doubled_to_140`, `straight_quarter_140_reads_140` (12 s and 30 s), `syncopated_76_is_not_doubled`. The existing trap and ballad tests are unchanged.

## 1.3.0 tempo octave and GPU export — November 18, 2026

- Per the owner's rule for this release, nothing was compiled, tested or rendered on the build box. The code was checked by reading it against the existing sources and the Android/Rust API documentation only.
- New Rust tests for the owner to run (`cd engine && cargo test`): `half_time_trap_reads_140_not_70` (12 s and 30 s), `slow_ballad_is_not_doubled` (76 BPM), `short_clip_tempo_is_precise` (12 s at 140, tempo and fitted grid within 0.3 BPM), `interpolation_passes_through_samples`.
- Not yet checked: everything on a phone, in particular GPU export speed versus 1.2.0, the CPU fallback, rotated (landscape-only) encoders, and tempo on real trap, ballad and drum-and-bass tracks.

## 1.2.0 media backgrounds — November 17, 2026

- One checkpoint run of `./gradlew --no-daemon check :app:assembleDebug` on the build box passed: 19 JVM tests (8 new in `BackgroundMathTest`: loop/hold mapping, invalid times, frame lookup, Render quality working sizes, even/never-upscaled sizes, Fill/Fit/zoom placement, reactivity limits). Lint reported 9 warnings, all from code that was already there (target/compile SDK versions, allowBackup, UsableSpace, four SetTextI18n); none came from the new code. The debug APK is 5.65 MB.
- `cargo test --release` in `engine/`: 17 passed (the engine is unchanged in this release).
- A desktop mock of the composite (`/workspace/soundwaive-previews/v1.2.0-background-preview.mp4`, built with ffmpeg on the box) used a synthesized song, a procedurally generated 4-second looping clip decoded at 360x640 and upscaled with bilinear filtering, and the crown pulsing on the engine's analysis of that song. It follows the same `BackgroundMath` formulas.
- Not yet checked on a phone: video decode across devices (rotation, 10-bit/HDR sources, long-GOP seeks), GIF timing, persisted URI permissions after reboot, and export speed at each Render quality.

## 1.1.0 beat grid and onset timing — November 17, 2026

Built locally on Linux (x86_64) with Temurin JDK 17.0.21, Gradle 9.6.1 wrapper, AGP 9.4.0, Android SDK 35, build tools 36.0.0, NDK 28.2.13676358 and Rust 1.100.0.

- `./gradlew --no-daemon check :app:assembleDebug`: **build passed**, including ARM64/ARMv7/x86_64 Rust libraries and demo regeneration with the new engine. Release APK/AAB tasks were not run for this change.
- **17 Rust tests passed** (`cargo test --locked`): FFT, normalization, moving mean/max, parabolic interpolation, onset timing on sub-frame kicks, quiet-intro whitening, no onsets on steady tones/silence, envelope placement, 120 BPM and fractional 123.4 BPM tempo, beat phase after a silent intro, downbeat from bass accents, silence/short input, deterministic output and the full file layout including `SWBEAT01`.
- **11 JVM tests passed**: 4 MP4 timing, 3 analysis-format compatibility (legacy file, beat grid, truncated/foreign trailer), 4 downsampler (output rate for 11.025-96 kHz, passband, Nyquist removal, clamping).
- **Android lint: 0 errors, 9 warnings** (unchanged from 1.0.1).
- `python3 tools/eval_sync.py --holdout` (synthetic songs, exact ground truth; 1.0.1 = main engine with the old sample-pick decimation, 1.1.0 = this branch with averaging decimation):

| Metric (mean) | 1.0.1 tuning | 1.1.0 tuning | 1.0.1 held-out | 1.1.0 held-out |
|---|---|---|---|---|
| Kick envelope F (±50 ms) | 0.939 | 0.959 | 0.967 | 0.976 |
| Kick timing bias / MAE | -10.3 / 10.7 ms | +4.5 / 6.4 ms | -10.2 / 11.7 ms | +4.4 / 7.0 ms |
| Snare envelope F | 0.537 | 0.670 | 0.584 | 0.805 |
| Burst onsets F / MAE | 0.916 / 16.5 ms | 0.889 / 4.7 ms | 0.957 / 17.4 ms | 0.957 / 4.9 ms |
| Tempo within 1% (octave-tolerant) | 3/8 (4/8) | 6/8 (8/8) | 1/3 (1/3) | 3/3 (3/3) |
| Beat F (±70 ms) | 0.492 | 0.907 | 0.497 | 0.997 |
| Downbeat F | 0.429 | 0.582 | 0.459 | 0.667 |

Envelope timing is quantized to the 23.2 ms analysis frame, so about 6 ms MAE is the floor for that metric. Remaining known limits: one global tempo per song (a mid-song tempo change is tracked at the dominant tempo), half/double-tempo ambiguity (trap and drum-and-bass are reported at half tempo), downbeats are a guess when no bass or chord change marks the bar, and whitening hides quiet hits within about 2 s of a much louder section.

Analysis CPU time for 180 s of audio on the x86_64 build machine: 357 ms (1.0.1) and 439 ms (1.1.0), best of three. Not measured on a phone.

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
