# Release Pack

Export › **Render Release Pack** turns one imported song into seven videos with the current look. Code: `ReleasePlan.kt` (window choice), `ReleasePack.kt` (per-clip look, render options, saving), `ExportService.runPack` (the queue) and `VideoExporter.Options` (per-render overrides).

## Outputs

| # | File | Size | Length | Audio | fps |
| --- | --- | --- | --- | --- | --- |
| 1 | `<song>_Vertical15_A.mp4` | 1080 × 1920 | 15 s | AAC 320k, fades 0.08 s in / 1 s out | look |
| 2 | `<song>_Vertical15_B.mp4` | 1080 × 1920 | 15 s | same | look |
| 3 | `<song>_Vertical15_C.mp4` | 1080 × 1920 | 15 s | same | look |
| 4 | `<song>_Reel_30s.mp4` | 1080 × 1920 | 30 s | same | look |
| 5 | `<song>_Square.mp4` | 1080 × 1080 | 30 s (setting: 15 / 30 / 60) | same | look |
| 6 | `<song>_Canvas_8s.mp4` | 1080 × 1920 | whole bars, 3–8 s | none | ≤ 30 |
| 7 | `<song>_YouTube_16x9.mp4` | 3840 × 2160 or 1920 × 1080 | full song | AAC 320k, the look's own fades | look |

They render in that order: short clips first so they are ready soonest, and the long YouTube render last. All are H.264 (High profile when the encoder lists it) in MP4, saved to `Movies/SoundWaive/<song>/`. `<song>` is the imported file name without its extension, with characters that file systems reject replaced by `_`. Rendering a pack again replaces the files from the last run (same names).

Bitrates are the look's video quality setting for the clips. The YouTube video uses 45 Mbps at 4K and 24/30 fps, or 68 Mbps at 4K and 60 fps (YouTube's recommended SDR upload rates). 4K is used only when `MediaCodecList.findEncoderForFormat` accepts 3840 × 2160 at that frame rate and bitrate, in either orientation. Otherwise the video falls back to 1080p, and the results say so.

Spotify Canvas spec: 9:16, 3–8 s, at least 720 px tall, MP4, no audio. The Canvas file meets all four.

## How windows are chosen

All from the analysis made at import. Nothing is analyzed again.

- **Level curve:** `.6 × loudness + .4 × bass` per analysis frame, the same curve the drop flash uses.
- **Strength** of a window is its mean level. A drop (from `BeatFx.findDrops`) in the first 35% of a window adds up to 0.25 × the drop's strength.
- **Start times** are downbeats from the beat grid. With a manual tempo override, or an analysis without a grid, they come from a 4/4 bar grid from 0 s, the same grid the renderer uses then.
- **Bar length** is the median downbeat spacing, otherwise four beats at the BPM, otherwise 2 s.
- **Picking:** among the candidate starts, take the window with the least overlap with the windows to avoid. Break ties by highest score, then earliest start. The result is deterministic, and a short song still gets every clip, just with some overlap.

| Clip | Score | Avoids |
| --- | --- | --- |
| Vertical A | strength + drop bonus | none |
| Vertical B (hook) | strength × (1 − 0.3 × start / duration), among windows that end before A (if A starts at 15 s or later) | A |
| Vertical C | strength | A and B, each widened by 10 s, so C comes from a different section, not the other half of A's chorus |
| Reel | strength + drop bonus | none |
| Square | strength + drop bonus | the reel |
| Canvas | strength, among bar starts inside A | none |

"Bridge" is not detected directly, because that would need harmonic or structural analysis the engine does not do. Vertical C is the strongest part away from A and B: usually the second chorus, a later drop or the bridge's lift.

## Fades

- Clips (A, B, C, reel, square): audio fades in over 0.08 s, which removes the click without blunting the downbeat, and fades out over 1 s. The picture starts on the hit and fades out over 0.6 s.
- YouTube: the look's own fade-in and fade-out over the whole song.
- Canvas: no fades. It loops.

## Seamless Canvas loop

1. Length: `canvasLength(bar)` is the largest whole number of bars that fits in 8 s. A bar over 8 s is halved until it fits. The result is always 3–8 s.
2. The render uses exactly `round(length × fps)` frames, so frame *f* and frame *f − N* are exactly one loop apart.
3. The 0.5 s just before the window are rendered first and held as bitmaps. Time only moves forward, so a video background keeps decoding in order.
4. Each of the last 0.5 s of frames is blended toward its held partner, from (k+1)/(n+1) up to n/(n+1). The last frame is almost the moment just before the window starts, so the jump back to frame 0 is continuous in motion, beat effects and background.
5. The Canvas has no audio track, and it uses the CPU drawing path, because the GPU path writes straight to the encoder and cannot blend two scene renders.

## Reframing

- Each clip sets its own frame shape. The background is always **Fill (crop)**, a cover crop with no letterbox or blurred bars.
- `fitSymbolToFrame` is set only on the pack's clip looks. It scales the symbol, and everything sized from it, by `min(1, frame height / frame width)`. In 16:9 the crown is about 56% of its portrait size, so it stays inside the 1080 × 608 renderer frame. 9:16, 4:5 and 1:1 are unchanged. Position and drift are already relative to the frame.

## Not done

- The audio sample rate is not converted to 48 kHz. SoundWaive has no resampler, so a 44.1 kHz song stays at 44.1 kHz, which every target platform accepts.
- The render-quality setting still applies, so for native 4K detail choose **Full (original)**.
