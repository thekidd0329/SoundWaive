# Verification

## 1.13.1 LiteRT Kotlin metadata — November 21, 2026

- Nothing was compiled, tested, rendered or run on a phone. The failed run was `44622036485` on `feature/ai-upscale` (commit `26df280`, 19 November 2026). `:app:compileDebugKotlin` failed with 77 errors of the form: LiteRT classes (`CompiledModel`, `Accelerator`, `TensorBuffer` and others) "compiled with an incompatible version of Kotlin. The actual metadata version is 2.4.0, but the compiler version 2.2.0 can read versions up to 2.3.0." The compiler also printed "use `-Xskip-metadata-version-check` to suppress errors." That flag is now set on `KotlinCompile` in `app/build.gradle.kts`.
- Also includes 1.12.1.
- Not yet checked: that the new GitHub Actions run for this branch finishes the assemble and lint steps. Phone checks from 1.13.0 are still outstanding.

## 1.13.0 AI background sharpening (LiteRT) — November 19, 2026

- Nothing was compiled, tested, rendered or run on a phone. The change was checked against:
  - the LiteRT 2.3.0 Kotlin API: the published reference, plus the class files of `litert-api-2.3.0.aar` for `CompiledModel.create(AssetManager, String, Options, Environment?)`, `Options(vararg Accelerator)`, `createInputBuffers`/`createOutputBuffers`, `TensorBuffer.writeFloat`/`readFloat`, `Environment.create(Context, NpuAcceleratorProvider)`, `getAvailableAccelerators` and `BuiltinNpuAcceleratorProvider(Context)`
  - the official `litert-samples` NPU app (minSdk 31, arm64-v8a, `useLegacyPackaging`)
  - Google Maven, where all three 2.3.0 artifacts exist
- The model was downloaded with curl from Qualcomm AI Hub's v0.67.0 release. Its metadata says input `image` [1,128,128,3] float32 RGB 0..1 and output `upscaled_image` [1,512,512,3]. sha256 `47dbfe34c3492e6f7f7fb2b0ed99794e8e41215c2177821f03e0f5304e4b9846`, checked by `verifyAssets`.
- No AOT Tensor G5 model is bundled; that needs Christian's Tensor SDK access (`scripts/aot_compile_g5.py`).
- Risks:
  - The Kotlin compiler must read LiteRT's Kotlin metadata 2.4. The official sample passes `-Xskip-metadata-version-check`.
  - The GPU-only options might silently fall back to the CPU, so the chip label could say GPU while the CPU did the work.
  - Video sharpening is slow: about 40 tiles per 480p frame.
- Not yet checked: everything on a phone, in particular:
  - which chip loads on the Pixel 10
  - the per-tile time
  - photo and video output quality and seams
  - cache reuse on re-export
  - that looks saved in 1.12 open with the switch off

## 1.12.1 CI download failure — November 21, 2026

- Nothing was compiled, tested or rendered on the build box. The failed run was `44620296172` on `feature/gpu-effects` (commit `418803d`, 19 November 2026). Gradle failed at project configuration with `Could not resolve all artifacts for configuration 'classpath'`, listing 40+ well-known libraries as missing from Google Maven, Maven Central and the Gradle Plugin Portal, including `org.jetbrains.kotlin:kotlin-stdlib:2.3.21` and `org.jetbrains:annotations:13.0`. The same artifacts return HTTP 200 from Maven Central today, and the 1.11.0 and 1.13.0 CI runs (minutes before and after) resolved that same classpath with the same Gradle 9.6.1 and Android Gradle Plugin 9.4.0. There is no app or shader change in this patch; it exists so the 1.12 build can run again.
- Not yet checked: that the new GitHub Actions run for this branch finishes the assemble and lint steps.

## 1.12.0 GPU effects pass and zero-copy video backgrounds — November 19, 2026

- Nothing was compiled, tested or rendered on the build box. The change was checked by reading it against the existing sources and the Android and Khronos docs:
  - `HardwareRenderer`/`RenderNode` (API 29): `setOpaque(false)`, `setContentRoot`, `setSurface`, `beginRecording`/`endRecording` and `createRenderRequest().syncAndDraw()` with its `SYNC_*` result bits
  - `MediaCodec.setOutputSurface` (API 23)
  - `SurfaceTexture.updateTexImage`/`getTransformMatrix`
  - `EGLExt.EGL_OPENGL_ES3_BIT_KHR` with `EGL_CONTEXT_CLIENT_VERSION` 3
  - GLSL ES 3.00 with `GL_OES_EGL_image_external_essl3`
  - `GLES30.glUniform1ui`
  - a framebuffer object for the 48 × 48 blur
- Compositing equivalence: `VisualRenderer` uses only source-over drawing (color filters, no blend modes). A transparent scene composited over the background is therefore the same as drawing it onto the background. The full-frame layers are repeated in the Canvas order with Paint-rounded alphas: darkening, flash, specks, vignette, titles, fade. Screen shake moves them, and mirror flips them.
- New JVM test to run (`./gradlew :app:testDebugUnitTest`): `BeatFxTest.grainSpecksStayInsideTheirCells`.
- Not yet checked: everything on a phone, in particular:
  - that the Pixel (Tensor G5, PowerVR) logs "GPU effects on (zero-copy video: true)" for a video-background export
  - export time against 1.11 with a color and a video background
  - that GPU and Canvas exports look the same (vignette, grain, flash, titles with shake and mirror, Fit video edges)
  - that forcing the fallback produces the notice and a correct video

## 1.11.0 sound wave line and ring — November 18, 2026

- Nothing was compiled, tested or rendered on the build box; the change was checked by reading it against the existing sources, the engine's analysis layout (`engine/src/lib.rs`: 256 waveform samples per frame at offset `8 + BANDS`, spanning the 2048-sample window at 11025 Hz) and the Android API docs (`Path.moveTo`/`lineTo`/`close` into one reused path, `Canvas.drawPath` with stroke paints).
- New JVM tests to run (`./gradlew :app:testDebugUnitTest`): in `BeatFxTest`, `waveTriggerFindsTheFirstRisingZeroCrossing`, `wavePeakAndSmoothing`, `waveGainScalesToThePeakAndTheEnergy`, `waveTaperAndMirroredRing`.
- Not yet checked: everything on a phone, in particular whether the wave reads as music on dense mixes (the stored samples are every 8th sample, so very high sounds alias into fuzz), how the line sits with Spectrum city's bars, preview and export frame time, and that looks saved in 1.10 open with both off.

## 1.10.0 sound blob and star warp — November 18, 2026

- Nothing was compiled, tested or rendered on the build box; the change was checked by reading it against the existing sources and the Android API docs (`Path.quadTo` through outline midpoints for a smooth closed curve, `Canvas.drawPath` with fill and stroke paints, `Canvas.scale(sx, sy, px, py)` for the inner outline undone by `restoreToCount`, `Canvas.drawLine` for the stars).
- New JVM tests to run (`./gradlew :app:testDebugUnitTest`): in `BeatFxTest`, `blobBandsMirrorWithBassAtTheBottom`, `blobRestsAsACircleAndSwellsWithTheSound`, `warpStarsTravelWithTheFlowAndFadeIn`.
- Not yet checked: everything on a phone, in particular whether the blob edge reads as music and not noise on real songs, how busy the frame gets with all fifteen effects on, preview and export frame time, and that looks saved in 1.9 open with the blob and warp off.

## 1.9.0 bounce on beats and orbit dots — November 18, 2026

- Nothing was compiled, tested or rendered on the build box; the change was checked by reading it against the existing sources and the Android API docs (`Canvas.scale(sx, sy, px, py)` pivots on the symbol's base and is undone by `restoreToCount` before the rings are drawn; `Canvas.drawCircle` with a fill paint for the dots).
- New JVM tests to run (`./gradlew :app:testDebugUnitTest`): in `BeatFxTest`, `bounceSquashesOnTheBeatAndSettles`, `bounceStretchesJustBeforeTheNextBeat`, `orbitStepsLandOnTheBeatAndJoinUp`.
- Not yet checked: everything on a phone, in particular whether the bounce feels on time on real songs, preview and export frame time with all thirteen effects on, and that looks saved in 1.8 open with the bounce and orbit dots off.

## 1.8.0 build-up before drops and tilt on beats — November 18, 2026

- Nothing was compiled, tested or rendered on the build box; the change was checked by reading it against the existing sources and the Android API docs (`Canvas.rotate(degrees, px, py)` and `Canvas.scale(sx, sy, px, py)` pivot around the symbol and are undone by the existing `restoreToCount`).
- New JVM tests to run (`./gradlew :app:testDebugUnitTest`): in `BeatFxTest`, `buildUpRisesIntoTheDrop`, `noBuildUpRightAfterAnotherDrop`, `tiltRocksBothWaysAndSettles`.
- Not yet checked: everything on a phone, in particular whether the build-up feels right before real drops, preview and export frame time with all eleven effects on, and that looks saved in 1.7 open with the build-up and tilt off.

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
