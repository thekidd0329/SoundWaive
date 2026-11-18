# Changes

## [1.7.0] - 2026-11-18

### Added
- Two more effects under **Beat effects** on the Studio screen, on for new looks and presets:
  - **Flash on drops:** when the song jumps from a quieter stretch into a loud one (a drop, a big chorus), the whole frame flashes and one wide wave sweeps from the symbol past the edges of the screen. Both fade with a cubic decay over 0.7 s. Drops are found once per song from loudness and bass (`0.6 * rms + 0.4 * low`): each frame's next 0.35 s is compared with its previous 2 s, and a jump of at least 0.22 up to a level of at least 0.5 counts. At most one drop every 6 s (the bigger jump wins), and a drop within 0.12 s of a tracked beat lands on that beat. Brightest flash is 45 % white at the default strength.
  - **Light rays on bass:** twelve beams fan out from behind the symbol and slowly turn with the music when the bass passes 0.3, brighter and wider as it rises (every other beam is narrower). They use the palette's second color.
  - Drop idea from the MIT-licensed le-director/director-signals ("drop impact": short window after against a longer one before) and 5ymph0en1x/Sythm (flash and shockwave on drops); no code copied.
  - **More options** adds **Drop flash strength** and **Light ray strength** (0 turns them off).
- `BeatFx`: `findDrops`, `dropFlash`, `rayAlpha` and `rayHalfAngle`, with JVM tests.
- `Analysis.dropTimes`, `dropStrengths`: the song's drops as primitive arrays, found when the song loads.

### Changed
- Stored looks are now `lookVersion` 5. A look stored before 1.7 loads with the two new switches off, so it looks the same as before; the rules for older looks are unchanged.
- **Ink storm** keeps both new effects (they use its own black and white colors). **Minimal pulse** turns both off.

### Performance
- Drop finding is one pass with prefix sums when the song loads; per frame it is a binary search. The rays are one reused `Path` and one draw call, drawn only while the bass is up; the flash is one rectangle for 0.7 s after a drop. No per-frame allocation.

## [1.6.0] - 2026-11-18

### Added
- Two more effects under **Beat effects** on the Studio screen, on for new looks and presets:
  - **Ripple on hits:** strong kicks and snares send a wave rolling out through the crown (or your own artwork), like a pebble in water. The picture is drawn as a 17 x 17 point mesh (`Canvas.drawBitmapMesh`) whose points move along the radius by a damped wave `sin(k(r - front))`; the front leaves the center at 1.6 symbol heights per second and the whole ripple fades with a cubic decay over 0.55 s. Largest shift is 3 % of the symbol height at the default strength. A weak hit right after a big one does not cut the big ripple short. Shapes drawn as outlines (Ring, Star and so on) are not bent.
  - **Rainbow edge on bright sounds:** cymbals, airy synths and other bright sounds push a red and a cyan copy out around the symbol (radial chromatic aberration). Brightness is the treble level weighted by the spectral centroid of the frame's 80 bands; dull or quiet passages stay clean. Works for every symbol.
  - Ripple and treble/centroid ideas from the MIT-licensed baditaflorin/synaesthete; no code copied.
  - **More options** adds **Ripple strength** and **Rainbow edge strength** (0 turns them off).
- `BeatFx`: `latestHit`, `rippleShift`, `spectralCentroid` and `fringeScale`, with JVM tests.
- `Analysis.onsetTimes`, `onsetStrengths`: hits sorted by time as primitive arrays.

### Changed
- Stored looks are now `lookVersion` 4. A look stored before 1.6 loads with the two new switches off, so it looks the same as before; the 1.4 and 1.5 rules for older looks are unchanged.
- **Ink storm** keeps the ripple but turns the rainbow edge off (black and white look). **Minimal pulse** turns both off.
- The snare color split copies ripple together with the symbol.

### Performance
- The ripple reuses one vertex array (289 points) and only replaces `drawBitmap` while a ripple is live; the rainbow edge is two extra bitmap draws on bright frames only, the same cost as the color split. No per-frame allocation.

## [1.5.0] - 2026-11-18

### Added
- Two more effects under **Beat effects** on the Studio screen, on for new looks and presets:
  - **Glow that changes color with the notes:** a soft glow behind the symbol. Each detected note sits on the circle of fifths (C red, G orange, D yellow, ... 30 degrees per fifth), the notes of the last 1.2 s are blended as velocity-weighted vectors with a cubic fade, and the result sets the hue. Notes that agree give a deep color, clashing ones a paler one; bass sets brightness and size. Without detected notes it uses the palette's main color. Key-color idea from the MIT-licensed dedo1911/music-visualizer; no code copied.
  - **Sparkles on hi-hats:** small four-point glints around the symbol when the hi-hat level passes 0.35, up to 7 at the default amount (14 at most). Positions come from a hash of music time, so preview and export match.
  - **More options** adds **Glow strength** and **Sparkle amount** (0 turns them off).
- `BeatFx`: `fifthsHue`, `noteColor`, `glowAlpha`, `sparkleCount` and `hash01` (lowbias32 integer hash, public domain), with JVM tests.
- `Analysis.noteTimes`, `noteMidi`, `noteVelocity`: notes sorted by time as primitive arrays.

### Changed
- Stored looks are now `lookVersion` 3. A look stored before 1.5 (version 1 or 2) loads with the two new switches off, so it looks the same as before; 1.4 rules for older looks are unchanged.
- **Ink storm** keeps sparkles but turns the colored glow off (black and white look). **Minimal pulse** turns both off.

### Performance
- The vignette gradient is built once and reused until its strength or the frame height changes, instead of a new `RadialGradient` every frame.
- Film grain uses the hash instead of a new `java.util.Random` every frame, and the paper texture reuses one `RectF`. Grain specks land in different (still repeatable) places than in 1.4.
- The glow is one 128 px alpha mask made at start-up and tinted by the paint color, so it adds no bitmap, shader or filter per frame.

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
