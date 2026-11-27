# SoundWaive — your music, your visual

Install **SoundWaive-dev.apk** from the GitHub build on your Android phone or tablet. Android may ask you to allow installation from your browser or Files app. The app works offline on Android 8.0 and newer, on ARM and Intel devices.

## Make a video

1. Open SoundWaive. A generated demo beat is ready to play.
2. Tap **Import music** to choose your FLAC or another audio file that Android supports. Wait for the beat and bass-note analysis.
3. Press **Play**. Choose a preset in **Studio**, then customize **Look** and **Motion**. Tap the expand button for a full-screen preview.
4. In **Export**, choose the shape, resolution, frame rate and quality. Leave the end time at **0** to include the whole song.
5. Tap **Export video**, choose a location in Files, and allow notifications if Android asks. You can leave the app; a notification shows the percentage until it finishes. SoundWaive reports the actual video settings if your encoder requires a smaller size, frame rate or bitrate. The MP4 includes your music. You can play or share it from the app.

## Keep it fresh

**New variation** changes the arrangement, colors, motion and layers. The two locks in Studio preserve your chosen symbol and its color. Turn a lock off when you want that part randomized too. **Save look** and **Load saved look** let you reuse a favorite combination.

The original look uses your drippy three-point crown, a constant white symbol color, flying music staves and dense reactive visuals. Text is off by default. The crown color stays fixed throughout a video unless you choose a different color yourself.

You can customize:

- **Identity:** nine built-in symbols, no symbol, or your own artwork; color, size, opacity, position and mirroring.
- **Atmosphere:** eight palettes, custom accent colors, a color, photo, GIF or video background (Dim and "Pulse with the beat" sit right under it), grain, vignette and glow.
- **Motion:** drift, rotation, zoom, shake, speed, smoothing, scene changes and variation seed.
- **Beat effects (Studio):** rings on every beat, a punch-in on the first beat of each bar and a color split on snares, all on by default. **More options** sets how strong each one is. **Beat sensitivity** and **Bass sensitivity** sit right above them.
- **Layers:** echoes, trails, rings, filaments, spectrum spokes/bars, grid, glitch strips and particles.
- **Music notation:** flying staves, note size, layer count and scroll speed.
- **Reaction:** bass/mids/highs, sensitivity, beat source, timing offset and tempo override.
- **Output:** 9:16, square, landscape or 4:5; 720p or 1080p; 24/30/60 fps; quality, trimming and picture fades.
- **Optional text:** title and subtitle, size, color and placement.

Transparent PNGs work best for custom symbols. Disable **Tint imported artwork** to preserve an image's own colors. Choose **Custom** in Palette to use the three custom accent controls.

## Practical notes

- Imported audio caches and exported videos use additional storage. A four-minute video at 12 Mbps is roughly 370 MB. Temporary cache (decoded audio, export scratch and AI video frames) is cleared when you open the app and after each export. Export › Storage shows the size and has Clear cache; saved looks and photos are not removed.
- Music stays on your device. There is no account, upload, subscription or Internet permission.
- Beat timing, energy and approximate bass pitches come from local analysis. The flying notes are expressive animation, not verified sheet music.
- Mono/stereo tracks up to 30 minutes are supported. Codec availability and export speed depend on the phone. If a size/frame-rate combination is unsupported, try 720p at 30 fps.
- Keep the app open during analysis. Exports keep running in the background, with a notification that shows the percentage. Cancel stops the operation and removes an unfinished video. The current song returns to the generated demo after a fresh app launch, unless an export is still running; saved looks and custom artwork persist.
- Settings files save controls. They do not contain your song or custom images; import those separately on another phone.
- The bundled demo is generated from oscillators and noise. Your unreleased music is not bundled. The crown is your supplied artwork.

The Kotlin/Rust source project is included separately for future changes.
