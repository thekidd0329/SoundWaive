# Architecture

SoundWaive is a native Android application with one Kotlin app module and a Rust analysis crate. There is no server, web runtime, account system or Internet permission.

## Music to video

1. Android's document picker returns the selected audio URI. `AudioEngine.kt` decodes it with `MediaExtractor`/`MediaCodec`, retains the PCM needed for export, and creates a mono 11025 Hz stream for analysis.
2. `engine/src/lib.rs` computes FFT frequency bands, energy envelopes, transients, tempo and approximate bass pitches. JNI returns the result of writing a versioned `CPULSE01` analysis file.
3. `Models.kt` validates analysis/settings data. `MainActivity.kt` coordinates playback, controls, saved looks, lifecycle, cancellation and file destinations.
4. `VisualRenderer.kt` draws a deterministic composition from music time, settings and seed. The same renderer powers preview and export, including custom artwork, crown textures, musical staves, particles and layers.
5. `VideoExporter.kt` renders timestamped frames through an OpenGL encoder surface and encodes AAC audio. Device encoders constrained to landscape can use a rotated frame plus MP4 orientation metadata. Unsupported combinations produce an actionable error.
6. `Mp4AudioTiming.kt` adjusts MP4 audio edits to remove the selected software AAC encoder's priming while retaining the full audible tail. Media-data offsets remain unchanged. JVM fixtures and native re-import measurements cover this behavior.

## Compatibility

The product name is SoundWaive. The existing package/JNI namespace, settings format and analysis signature deliberately remain compatible with the original CrownPulse build. Changes to binary formats need explicit version handling and migration.

## Storage and lifecycle

Imported music and generated analysis stay in app-local storage. Export uses a user-selected URI. A fresh app launch returns to the bundled generated demo; saved looks and imported artwork persist. Analysis/export run off the UI thread but require the app to remain open. Cancellation cleans incomplete output. Audio focus and headphone disconnection pause playback.

## Testing boundaries

Host Rust tests cover FFT, normalization, tempo and analysis serialization. JVM tests cover timing metadata and non-destructive failure cases. Android lint and real native compilation cover the Android surface. Optional emulator instrumentation exercises decoding, JNI, rendering, export and artwork import using private fixtures.

Numerical beat and pitch estimates are intended for animation. Do not present them as guaranteed transcription. Physical-device codec support, thermal behavior, export speed and long-running memory use need measurement on representative phones.
