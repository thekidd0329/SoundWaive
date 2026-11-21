# SoundWaive release notes

## 1.13.0 — AI-sharpened backgrounds

**What's new**
- **Sharper, cleaner backgrounds.** A new switch, **Sharpen & clean up background (AI)**, makes small or blurry background photos and videos sharp and removes noise and blocky compression. It runs on the Pixel's AI chip (TPU) when it can, otherwise on the graphics chip.
- When your video is ready, the message tells you which chip did the work.

**Where to find it**
- Studio › **Background**, when the background is a photo or a video. It's on for new looks.

**Good to know**
- A photo is sharpened once when you pick it, so the preview and every export use the sharp version.
- Video backgrounds are sharpened while exporting, so the first export with a low-resolution video takes longer. Exporting the same clip again reuses the work.
- Looks you saved before 1.13 keep their background exactly as it was (the switch is off).
- This version needs Android 12 or newer on a 64-bit phone (it's built for your Pixel).

## 1.12.1 — Build fix

- No changes to the app. The 1.12.0 package did not finish building on GitHub because a Maven download failed; this release is that same 1.12 work, rebuilt.

## 1.12.0 — Faster export on the GPU

**What's new**
- **Faster exports.** The vignette, film grain, flash on drops, build-up darkening, titles and fades are now added by your phone's graphics chip in one step while the video is written, instead of being painted one layer at a time.
- **Much faster video backgrounds.** A video background now goes straight from the phone's video decoder to the graphics chip without being copied in between. Exports with a video background should be the biggest speed-up.

**Good to know**
- Your videos should look the same as before, and saved looks are unchanged.
- If your phone can't use the fast path, the export still works the old way, and the "Your video is ready" message says so in one line.
- The preview on screen works the same as before.

## 1.11.0 — Sound wave line and ring

**What's new**
- **A sound wave ring around your symbol.** The actual shape of your song's sound wraps around the crown as a glowing loop. It wiggles with every sound (vocals, synths, hi-hats, not just the beat) and gets thicker when the bass hits.
- **A sound wave line across the screen.** The classic oscilloscope look: your song's sound wave as a glowing line running across the video behind the crown. Steady notes hold still instead of sliding around.

**Where to find it**
- Studio › **Beat effects**: one switch for each. The ring is on for new looks. The line is off unless you turn it on, or pick **Spectrum city** or **Ink storm**.
- Tap **More options** to make either one taller or flatter.

**Good to know**
- Looks you saved before 1.11 open exactly as before, with both off.
- These use sound data the app already had, so analysis is not any slower.
- Minimal pulse skips both.

## 1.10.0 — Sound shape and star warp

**What's new**
- **A shape that moves with the sound.** A soft glowing shape sits behind the crown and its edge dances with the music: the bass moves the bottom, the highs move the top, and the whole shape swells when the bass hits.
- **Star warp.** Streaks of light fly out from behind the crown to the edges of the screen, like flying through space. They race in the loud parts, surge on kicks and drift slowly in the quiet parts.

**Where to find it**
- Studio › **Beat effects**: one switch for each. They are on for new looks and presets.
- Tap **More options** to make either one stronger or softer.

**Good to know**
- Looks you saved before 1.10 open exactly as before, with these two effects off. Turn them on in **Beat effects** if you want them.
- On **Battery saver** the preview shows fewer stars; the exported video always has all of them.
- Minimal pulse skips both new effects.

## 1.9.0 — Bounce and orbit dots

**What's new**
- **Bounce on beats.** The crown squashes down on every beat, springs back up and settles, like it's landing on the beat. Right before the next beat it stretches up a little, so each hit lands with a snap.
- **Dots that orbit on the beat.** Four little dots circle the crown and jump forward on every beat, so you can see the beat even with the sound off. They go all the way around every two bars and get bigger on the first beat of each bar.

**Where to find it**
- Studio › **Beat effects**: one switch for each. They are on for new looks and presets.
- Tap **More options** to make the bounce stronger or softer, or the dots bigger or smaller.

**Good to know**
- Looks you saved before 1.9 open exactly as before, with these two effects off. Turn them on in **Beat effects** if you want them.
- Only the crown bounces; the rings, rays, background and titles stay put.
- Minimal pulse skips both new effects.

## 1.8.0 — Build-up and beat tilt

**What's new**
- **Build-up before drops.** In the few seconds before a drop, the picture slowly pushes in on the crown, the screen gets a little darker and rings start falling into the crown on every beat. It all tightens right up to the drop, then lets go as the flash hits.
- **Tilt on beats.** The crown and its effects lean a little on each beat, left then right, like the camera is rocking with the song.

**Where to find it**
- Studio › **Beat effects**: one switch for each. They are on for new looks and presets.
- Tap **More options** to make the build-up or the tilt stronger or softer.

**Good to know**
- Looks you saved before 1.8 open exactly as before, with these two effects off. Turn them on in **Beat effects** if you want them.
- The build-up only shows before the drops SoundWaive finds, so songs without a clear drop won't have one. It works even if you turned **Flash on drops** off.
- Your background and titles stay level when the picture tilts.
- Minimal pulse skips both new effects.

## 1.7.0 — Drop flash and light rays

**What's new**
- **Flash on drops.** When your song hits a drop or a big loud section, the screen flashes and a big wave rushes out from the crown. It finds the drops on its own and lines them up with the beat.
- **Light rays on bass.** Beams of light fan out from behind the crown when the bass comes in, and get brighter and wider the harder it hits.

**Where to find it**
- Studio › **Beat effects**: one switch for each. They are on for new looks and presets.
- Tap **More options** to make the flash or the rays stronger or softer.

**Good to know**
- Looks you saved before 1.7 open exactly as before, with these two effects off. Turn them on in **Beat effects** if you want them.
- Songs without a clear jump in loudness may have no flash at all, and there is never more than one flash every 6 seconds.
- Minimal pulse skips both new effects.

## 1.6.0 — Ripples and rainbow edges

**What's new**
- **Ripple on hits.** Big kicks and snares send a wave rolling through the crown, like dropping a pebble in water. It settles in about half a second.
- **Rainbow edge on bright sounds.** Cymbals, airy synths and other bright sounds give the crown a red and blue edge that grows with the sound. Darker, quieter parts stay clean.

**Where to find it**
- Studio › **Beat effects**: one switch for each. They are on for new looks and presets.
- Tap **More options** to make the ripple or the rainbow edge stronger or softer.

**Good to know**
- Looks you saved before 1.6 open exactly as before, with these two effects off. Turn them on in **Beat effects** if you want them.
- The ripple bends the crown and your own artwork. Simple outline shapes (Ring, Star and so on) get the rainbow edge but don't ripple.
- Ink storm keeps the ripple but skips the rainbow edge; Minimal pulse skips both.

## 1.5.0 — Color glow and sparkles

**What's new**
- **A glow that follows the music.** A soft glow sits behind the crown and slowly changes color with the notes in your song. When the chords change, the color drifts with them. The bass makes it brighter.
- **Sparkles on hi-hats.** Tiny star glints pop around the crown on busy hi-hats and cymbals. Quiet parts stay clean.
- **Slightly faster.** The vignette and film grain do less work every frame, which helps preview and export on the phone.

**Where to find it**
- Studio › **Beat effects**: one switch for each. They are on for new looks and presets.
- Tap **More options** to make the glow stronger or softer, or to get more or fewer sparkles.

**Good to know**
- Looks you saved before 1.5 open exactly as before, with these two effects off. Turn them on in **Beat effects** if you want them.
- The black and white looks (Ink storm, Minimal pulse) skip the colored glow.
- Film grain specks are in slightly different spots than before; it looks the same overall.

## 1.4.1 — Saved looks stay as they were

- Looks you saved before 1.4 now open exactly as you made them, with the beat effects off. Turn them on in Studio › **Beat effects** if you want them. New looks and presets still start with the effects on.
- Straight 140 BPM songs with a strong 1 and 3 no longer read as 70.

## 1.4.0 — Beat effects

Your videos now hit harder on the beat, without any new settings to learn.

**What's new**
- **Rings on every beat.** A thin ring flies out of the crown on every beat, and a bigger double ring marks the first beat of each bar.
- **Punch in on the 1.** The picture zooms in a touch on the first beat of each bar and eases back, like a camera punch-in. Your background and titles stay still.
- **Color split on snares.** Loud snare hits split the crown into red and cyan copies for a split second. Quiet parts stay clean.
- **Bass sensitivity is on the main screen**, right under Beat sensitivity.

**Where to find it**
- Studio › **Beat effects**: one switch per effect. They are on by default.
- Tap **More options** under them to make each effect stronger or softer.

**Good to know**
- The effects follow the beats and bars SoundWaive finds in your song, so the preview and the exported video match exactly.
- If you typed a tempo override, the effects follow that tempo instead.
- Your saved looks still work and keep their original appearance (from 1.4.1): looks saved before 1.4 open with the beat effects off.
- No flashing: the effects are thin rings, a small zoom and a colored edge, not full-screen strobes.
