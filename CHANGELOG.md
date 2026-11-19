# Changes

## [1.4.1] - 2026-11-18

### Fixed
- Looks saved before 1.4 keep their original appearance. Looks now carry a `lookVersion` (2 = beat effects). A stored look without one (the current session, a saved look or an imported settings file from 1.3 or earlier) loads with **Rings on every beat**, **Punch in on the first beat of each bar** and **Color split on snares** off; a switch the look already has is kept. New looks, **Reset controls**, first launch and presets still get the effects on. This replaces the 1.4.0 "Compatibility" note below.
- The **Minimal pulse** preset spells out its beat effects (rings and punch on, color split off) instead of relying on the defaults.

### Changed
- Includes 1.3.1 (tempo octave fix for straight 140 BPM songs).

## [1.4.0] - 2026-11-18

### Added
- Beat effects, on by default and listed under **Beat effects** on the Studio screen:
  - **Rings on every beat:** a thin ring leaves the symbol on each tracked beat and a bolder double ring on the first beat of each bar. Rings use a quadratic ease-out and a cubic fade; beat strength from the grid sets their brightness.
  - **Punch in on the first beat of each bar:** a short zoom (up to 5 % at the default strength) of the effect layers around the symbol that settles within 0.42 s. Background, grain, vignette and titles stay still.
  - **Color split on snares:** loud snare hits draw red and cyan copies of the symbol a few pixels to each side (RGB split), scaled by the snare level and off in quiet passages.
  - **More options** shows a strength slider for each (0 turns it off).
- **Bass sensitivity** now sits on the Studio screen next to **Beat sensitivity** (same setting as Motion › Bass response).
- `BeatFx.kt`: pure, allocation-free timing and easing rules for the effects, with JVM tests (`BeatFxTest`).

### Changed
- The analysis keeps the beat grid as primitive arrays (`beatTimes`, `beatStrengths`, `beatIsDownbeat`, `downbeatTimes`), so effect lookups are binary searches with no per-frame allocation. Without a grid (older analysis files) or with a manual tempo override, the effects follow a fixed 4/4 grid from 0 s, the same rule scene changes use.
- The symbol's bitmap rectangle is reused instead of allocated on every draw.
- The **Minimal pulse** preset leaves the color split off.

### Compatibility
- Saved looks need no migration: looks from 1.3.0 and earlier have no beat-effect keys and get the defaults (all on, strength 1). The three strengths are clamped to 0–2 when a look loads.

## [1.3.1] - 2026-11-18

### Fixed
- Straight 140 BPM songs with accented 1 and 3 could still read as 70. The doubling check compared the fast beats with the eighths between them instead of looking halfway between the slow beats. It now doubles when nearly every midpoint between the slow beats has a real attack (at least 75 % of them, each at least a quarter of the on-beat strength). A slow groove with only an occasional off-beat kick stays slow. Half-time trap still reads 140 through its sixteenth-note cue, and the halving check is unchanged.

### Added
- Rust tests for straight quarters at 140 (octave check and full tempo at 12 s and 30 s) and a syncopated 76 BPM groove that must not double.

## [1.3.0] - 2026-11-18

### Fixed
- Half-time grooves no longer read at half speed: a 140 BPM trap beat read as 68-70 BPM. After the comb picks a tempo, an octave check moves to the double (or half) only when the log-normal prior around 120 BPM prefers it and the onsets agree (attacks between the slow beats for doubling, weak alternate beats for halving). Slow songs without in-between attacks stay slow.
- Tempo precision: the comb interpolated the autocorrelation linearly, which can only peak on whole-frame lags, so estimates were pinned to `fps * 60 / k` (70 read as 69.82, and 140 could only be 136.0 or 143.6 from the fundamental). It now uses Catmull-Rom interpolation plus a parabolic refinement of the best 0.05 BPM step.
- The beat-time least-squares fit could override the comb on short clips (the 12 s preview read 68.3 BPM); it is now accepted only within 1 % of the comb estimate.
- BPM labels round instead of truncating (139.98 shows as 140).

### Changed
- Faster export: frames are drawn with a hardware-accelerated Canvas into a SurfaceTexture that the encoder's GLES pass samples directly, replacing the CPU-drawn ARGB bitmap and its per-frame texture upload. Exact presentation timestamps, rotated encoders, backgrounds and Render quality upscaling are unchanged. If the GPU path cannot start or fails mid-export, the remaining frames fall back to the previous CPU path. Attribute and uniform locations are looked up once instead of every frame.
- Decoding reads each codec buffer in bulk into reused arrays and writes the mono analysis stream in one call per buffer instead of byte by byte.

### Added
- Rust tests for half-time trap at 12 s and 30 s, a 76 BPM ballad that must not double, short-clip precision and the interpolator.

## [1.2.0] - 2026-11-17

### Added
- Media backgrounds: choose **Color**, **Photo**, **GIF** or **Video** behind all effects from the system picker (no new permissions). Video is decoded with MediaExtractor/MediaCodec into a SurfaceTexture, scaled and rotated by a small GLES pass into reused buffers; its own audio is never used. GIFs play through `android.graphics.Movie`, which can render any time position.
- Backgrounds follow music time, not the wall clock: exports are deterministic and the preview shows the same frame. Clips loop or hold their last frame.
- Background options in plain language: **Dim**, **Pulse with the beat** (subtle bass zoom and beat brightening, on by default), and under **More options** **Fit** (Fill/crop or Fit with blurred edges) and **When the clip ends**.
- **Render quality** for exports: Fast (360p, upscaled), Balanced (720p, default) or Full (original). The frame is drawn at that size and upscaled with bilinear filtering by the encoder's GPU pass; background media are decoded at the matching working size. The preview always uses a 360p background.
- Saved looks keep the chosen GIF/video through a persisted read permission; a moved or deleted file falls back to the background color with a short note.
- JVM tests for background timing (loop/hold, frame lookup), working sizes, placement and reactivity limits.

### Changed
- The old "Use my background image" switch becomes the Background choice; saved looks migrate automatically (on → Photo, off → Color). Changing presets keeps your background.

## [1.1.0] - 2026-11-17

### Added
- Beat and bar tracking: tempo is measured to 0.05 BPM by fractional-lag harmonic autocorrelation with a tempo prior, beats follow dynamic programming (Ellis 2007) snapped to detected hits, and downbeats come from bass attacks plus bass-note/chord changes.
- Optional `SWBEAT01` beat-grid section after the onset list in analysis files. Older builds stop reading before it; the `CPULSE01` layout and JNI contract are unchanged.
- `tools/eval_sync.py`: reproducible synthetic benchmark (8 tuning + 3 held-out songs with exact ground truth) for kick/snare envelopes, onsets, tempo, beats and downbeats.
- Rust tests for onset timing, adaptive thresholds, fractional tempo, beat phase, downbeats, silence, short input and file layout; JVM tests for analysis-format compatibility and downsampling.

### Changed
- Onsets use a dedicated 46 ms window: linear bass-band flux for kicks, vibrato-suppressed log flux for body and air bands, offline adaptive thresholds, sub-frame peak timing and measured latency correction.
- Snare reactions remove body flux explained by kicks and hats. Particle-burst onsets are the merged kick and snare hits.
- Scene changes follow tracked bar lines when a beat grid exists; a manual tempo override keeps fixed-length bars.

### Fixed
- Kick, snare and burst timing no longer lands 10-17 ms before the attack.
- Android decoding averages samples when converting to 11025 Hz mono instead of picking single samples, so hats and cymbals no longer alias into the snare band.

## 1.0.1 — SoundWaive repository integration

- Replaced the unreleased song and its analysis with a reproducible generated demo beat.
- Named the Android application and interface SoundWaive while preserving the original application identity, saved settings and JNI compatibility.
- Added a conventional root Gradle project, checked wrapper, pinned Rust toolchain, explicit build inputs and three-ABI native compilation.
- Added optional environment-based release signing and a separate development app identity.
- Added GitHub Actions builds, downloadable development APK/unsigned release packages, automated Rust and MP4 timing tests, lint, and asset integrity checks.
- Fixed an Android 8 theme compatibility issue and mapped extractor flags explicitly during audio muxing.
- Documented setup, architecture, usage, private media rights and verification limits.

## 1.0.0 — Original CrownPulse build

- Offline audio analysis, customizable native renderer and audio-synchronized MP4 export.
- Original crown preset, eight looks, custom artwork and palettes, flying bass notation, randomized variations and saved settings.
- Android emulator validation with the full independently selected FLAC and short exported clips.
