//! Deterministic, offline music analysis. No network and no learned model. The optional
//! `lyrics` feature adds offline word-timed lyrics alignment (see [lyrics]).
//!
//! Output is the versioned `CPULSE01` file read by `Models.kt`, optionally followed by a
//! `SWBEAT01` beat-grid section that older readers ignore (they stop after the onset list).
pub mod beat;
pub mod dsp;
pub mod lyrics;
pub mod onset;

use dsp::{fft, hann, load_frame, normalize, quantile, smooth, Complex};
use onset::{Detection, PickParams, ShortSpectrum};
use std::{
    fs,
    io::{self, Write},
    path::Path,
};
const N: usize = 2048;
const HOP: usize = 256;
const RATE: f32 = 11025.0;
const BANDS: usize = 80;
const WAVES: usize = 256;
const STRIDE: usize = 8 + BANDS + WAVES;
/// Log bands below ~400 Hz (bass register) used for harmonic-change downbeat cues.
const BASS_BANDS: usize = 42;
/// Decay of kick/snare/hat envelopes; `VisualRenderer` derives ring age from it.
const HIT_DECAY: f32 = 0.15;
/// Hits weaker than this fraction of a typical strong hit are ignored.
const HIT_FLOOR: f32 = 0.3;
/// Trailing extension magic, version 1: u32 count, then count x [time, strength, downbeat].
pub const BEAT_MAGIC: &[u8; 8] = b"SWBEAT01";
#[derive(Debug, Clone)]
pub struct Report {
    pub bpm: f32,
    pub frames: usize,
    pub notes: usize,
    pub beats: usize,
}
pub fn analyze_pcm(input: &Path, output: &Path) -> io::Result<Report> {
    let bytes = fs::read(input)?;
    if bytes.len() < 2205 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Choose an audio file longer than 0.1 seconds",
        ));
    }
    if bytes.len() > RATE as usize * 2 * 60 * 30 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Please use a track of 30 minutes or less",
        ));
    }
    let pcm: Vec<f32> = bytes
        .chunks_exact(2)
        .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
        .collect();
    let frames = pcm.len().div_ceil(HOP);
    let fps = RATE / HOP as f32;
    let mut data = vec![0.0f32; frames * STRIDE];
    let mut low = vec![0.0; frames];
    let mut mid = low.clone();
    let mut high = low.clone();
    let mut rms = low.clone();
    let mut pitches = vec![0; frames];
    let mut a = vec![Complex::default(); N];
    let mut magnitude = vec![0.0; N / 2];
    let window = hann(N);
    let mut short = ShortSpectrum::new();
    let mut detection = Detection::with_capacity(frames);
    for f in 0..frames {
        let center = f * HOP;
        let lo = center.saturating_sub(N / 2);
        let hi = (center + N / 2).min(pcm.len());
        let energy: f32 = pcm
            .get(lo..hi)
            .map_or(0.0, |w| w.iter().map(|v| v * v).sum());
        rms[f] = (energy / N as f32).sqrt();
        detection.push(short.flux(&pcm, center));
        load_frame(&pcm, center, &window, &mut a);
        fft(&mut a);
        for k in 1..N / 2 {
            let m = a[k].norm() / N as f32;
            magnitude[k] = m;
            let hz = k as f32 * RATE / N as f32;
            if hz < 220.0 {
                low[f] += m * m;
            } else if hz < 2200.0 {
                mid[f] += m * m;
            } else {
                high[f] += m * m;
            }
        }
        low[f] = low[f].sqrt();
        mid[f] = mid[f].sqrt();
        high[f] = high[f].sqrt();
        for b in 0..BANDS {
            let lo = 25.0f32 * (5000.0f32 / 25.0).powf(b as f32 / BANDS as f32);
            let hi = 25.0f32 * (5000.0f32 / 25.0).powf((b + 1) as f32 / BANDS as f32);
            let k0 = (lo * N as f32 / RATE).floor() as usize;
            let k1 = ((hi * N as f32 / RATE).ceil() as usize)
                .min(N / 2 - 1)
                .max(k0 + 1);
            let v = magnitude[k0..k1].iter().copied().fold(0.0, f32::max);
            data[f * STRIDE + 8 + b] = (1.0 + v * 600.0).ln();
        }
        for w in 0..WAVES {
            let at = center as isize + (w * N / WAVES) as isize - (N / 2) as isize;
            data[f * STRIDE + 8 + BANDS + w] = if at >= 0 {
                *pcm.get(at as usize).unwrap_or(&0.0)
            } else {
                0.0
            };
        }
        let mut best = (0.0, 0);
        for midi in 28..60 {
            let hz = 440.0 * 2.0f32.powf((midi as f32 - 69.0) / 12.0);
            let mut sal = 0.0;
            for harmonic in 1..=4 {
                let bin = hz * harmonic as f32 * N as f32 / RATE;
                let k = bin.floor() as usize;
                let frac = bin - k as f32;
                if k + 1 < magnitude.len() {
                    sal += (magnitude[k] * (1.0 - frac) + magnitude[k + 1] * frac)
                        / (harmonic as f32).powf(0.8);
                }
            }
            if sal > best.0 {
                best = (sal, midi);
            }
        }
        if rms[f] > 0.003 {
            pitches[f] = best.1;
        }
    }
    detection.finish();
    // Bass-register log spectrum (frames x BASS_BANDS) for harmonic-change downbeat cues.
    let bass_bands: Vec<f32> = data
        .chunks_exact(STRIDE)
        .flat_map(|r| r[8..8 + BASS_BANDS].iter().copied())
        .collect();
    let mut kicks = onset::pick(&detection.low, fps, PickParams::KICK);
    let mut snares = onset::pick(&detection.snare, fps, PickParams::SNARE);
    let mut hats = onset::pick(&detection.high, fps, PickParams::HAT);
    for p in [&mut kicks, &mut snares, &mut hats] {
        onset::normalize_strengths(p, HIT_FLOOR);
    }
    // Salient hits (kicks and snares/body attacks) drive bursts and beat snapping.
    let salient = onset::merge(&kicks, &snares, 0.03);
    let onset_times: Vec<f32> = salient.iter().map(|p| p.time).collect();
    let grid = beat::grid(
        &detection.pulse,
        &detection.low,
        &bass_bands,
        BASS_BANDS,
        &onset_times,
        fps,
        onset::LATENCY,
    );
    let bpm = grid
        .as_ref()
        .map(|g| g.bpm)
        .or_else(|| beat::tempo(&detection.pulse, fps))
        .unwrap_or(120.0);
    for v in [&mut low, &mut mid, &mut high, &mut rms] {
        normalize(v);
    }
    let low = smooth(&low, 0.65, 0.28);
    let mid = smooth(&mid, 0.65, 0.28);
    let high = smooth(&high, 0.8, 0.4);
    let slow = smooth(&rms, 0.025, 0.025);
    let kick = onset::envelope(&kicks, frames, fps, HIT_DECAY);
    let snare = onset::envelope(&snares, frames, fps, HIT_DECAY);
    let hat = onset::envelope(&hats, frames, fps, HIT_DECAY);
    let spectrum: Vec<f32> = data
        .chunks_exact(STRIDE)
        .flat_map(|r| r[8..8 + BANDS].iter().copied())
        .collect();
    let cap = quantile(&spectrum, 0.97).max(0.01);
    for f in 0..frames {
        data[f * STRIDE..f * STRIDE + 8].copy_from_slice(&[
            low[f], mid[f], high[f], rms[f], slow[f], kick[f], snare[f], hat[f],
        ]);
        for b in 0..BANDS {
            data[f * STRIDE + 8 + b] = (data[f * STRIDE + 8 + b] / cap).clamp(0.0, 1.0);
        }
    }
    let mut notes: Vec<[f32; 4]> = Vec::new();
    let mut last = 0;
    let mut pitch = 0;
    for f in 2..frames {
        let mut five = [
            pitches[f.saturating_sub(2)],
            pitches[f - 1],
            pitches[f],
            pitches[(f + 1).min(frames - 1)],
            pitches[(f + 2).min(frames - 1)],
        ];
        five.sort();
        let p = five[2];
        if p > 0
            && (p != pitch || kick[f] > 0.65)
            && f - last > (fps * 0.12) as usize
            && low[f] > 0.08
        {
            if let Some(prev) = notes.last_mut() {
                prev[2] = ((f - last) as f32 / fps).clamp(0.1, 1.5);
            }
            notes.push([f as f32 / fps, p as f32, 0.25, low[f]]);
            last = f;
            pitch = p;
        }
    }
    // Salient onsets drive particle bursts; tiny ripples are dropped.
    let onsets: Vec<[f32; 2]> = salient
        .iter()
        .filter(|p| p.strength >= 0.25)
        .map(|p| [p.time, p.strength])
        .collect();
    let mut out = io::BufWriter::new(fs::File::create(output)?);
    out.write_all(b"CPULSE01")?;
    for v in [
        pcm.len() as f32 / RATE,
        fps,
        bpm,
        frames as f32,
        BANDS as f32,
        WAVES as f32,
    ] {
        out.write_all(&v.to_le_bytes())?;
    }
    for v in &data {
        out.write_all(&v.to_le_bytes())?;
    }
    out.write_all(&(notes.len() as u32).to_le_bytes())?;
    for note in &notes {
        for v in note {
            out.write_all(&v.to_le_bytes())?;
        }
    }
    out.write_all(&(onsets.len() as u32).to_le_bytes())?;
    for on in &onsets {
        for v in on {
            out.write_all(&v.to_le_bytes())?;
        }
    }
    let beats = grid.map(|g| g.beats).unwrap_or_default();
    out.write_all(BEAT_MAGIC)?;
    out.write_all(&(beats.len() as u32).to_le_bytes())?;
    for b in &beats {
        for v in [b.time, b.strength, if b.downbeat { 1.0 } else { 0.0 }] {
            out.write_all(&v.to_le_bytes())?;
        }
    }
    out.flush()?;
    Ok(Report {
        bpm,
        frames,
        notes: notes.len(),
        beats: beats.len(),
    })
}
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_parker_crownpulse_NativeEngine_analyze(
    mut env: jni::JNIEnv,
    _class: jni::objects::JObject,
    input: jni::objects::JString,
    output: jni::objects::JString,
) -> jni::sys::jstring {
    let result = (|| -> Result<(), String> {
        let i: String = env.get_string(&input).map_err(|e| e.to_string())?.into();
        let o: String = env.get_string(&output).map_err(|e| e.to_string())?.into();
        analyze_pcm(Path::new(&i), Path::new(&o)).map_err(|e| e.to_string())?;
        Ok(())
    })();
    env.new_string(result.err().unwrap_or_default())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_parker_crownpulse_NativeEngine_lyricsAvailable(
    _env: jni::JNIEnv,
    _class: jni::objects::JObject,
) -> jni::sys::jboolean {
    lyrics::available() as jni::sys::jboolean
}
/// Aligns lyrics to the decoded song. Takes the interleaved 16-bit PCM file the importer
/// wrote, so the song never crosses JNI as one huge array. Returns JSON (see [lyrics]).
#[cfg(target_os = "android")]
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_parker_crownpulse_NativeEngine_alignLyrics(
    mut env: jni::JNIEnv,
    _class: jni::objects::JObject,
    pcm: jni::objects::JString,
    sample_rate: jni::sys::jint,
    channels: jni::sys::jint,
    text: jni::objects::JString,
    model_dir: jni::objects::JString,
    vad_threshold: jni::sys::jfloat,
    stability: jni::sys::jboolean,
) -> jni::sys::jstring {
    let json = (|| -> Result<String, String> {
        let pcm: String = env.get_string(&pcm).map_err(|e| e.to_string())?.into();
        let text: String = env.get_string(&text).map_err(|e| e.to_string())?.into();
        let dir: String = env
            .get_string(&model_dir)
            .map_err(|e| e.to_string())?
            .into();
        #[cfg(feature = "lyrics")]
        {
            Ok(lyrics::align_json(&lyrics::Request {
                pcm: Path::new(&pcm),
                sample_rate: sample_rate.max(0) as u32,
                channels: channels.max(1) as usize,
                lyrics: &text,
                model_dir: Path::new(&dir),
                vad_threshold: vad_threshold.max(0.0),
                stability: stability != 0,
            }))
        }
        #[cfg(not(feature = "lyrics"))]
        {
            let _ = (
                pcm,
                text,
                dir,
                sample_rate,
                channels,
                vad_threshold,
                stability,
            );
            Err("Lyrics are not available on this phone's processor.".into())
        }
    })()
    .unwrap_or_else(|e| format!("{{\"error\":{:?}}}", e));
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::f32::consts::PI;

    fn write_pcm(path: &Path, samples: impl Iterator<Item = f32>) {
        let mut pcm = Vec::new();
        for v in samples {
            pcm.extend_from_slice(&((v.clamp(-1.0, 1.0) * 32767.0) as i16).to_le_bytes());
        }
        fs::write(path, pcm).unwrap();
    }

    fn f32_at(b: &[u8], at: usize) -> f32 {
        f32::from_le_bytes(b[at..at + 4].try_into().unwrap())
    }

    fn u32_at(b: &[u8], at: usize) -> usize {
        u32::from_le_bytes(b[at..at + 4].try_into().unwrap()) as usize
    }

    /// Kick-like pulses on a 120 BPM grid, every fourth one louder with a bass note.
    fn groove(i: usize) -> f32 {
        let t = i as f32 / RATE;
        let beat = 0.5;
        let k = (t / beat).floor();
        let age = t - k * beat;
        let accent = if k as i32 % 4 == 0 { 1.0 } else { 0.6 };
        let kick = (2.0 * PI * (50.0 * age + 3.0 * (1.0 - (-age / 0.03).exp()))).sin()
            * (-age / 0.12).exp();
        let bar = (t / 2.0).floor() as i32;
        let hz = [82.4, 98.0, 73.4, 110.0][(bar % 4) as usize];
        let bass = 0.3 * (2.0 * PI * hz * t).sin() * (-(t - bar as f32 * 2.0) / 0.6).exp();
        0.7 * accent * kick + bass
    }

    #[test]
    fn full_analysis_roundtrip() {
        let root = std::env::temp_dir();
        let input = root.join("crownpulse_test.pcm");
        let output = root.join("crownpulse_test.bin");
        write_pcm(
            &input,
            (0..44100).map(|i| {
                let t = i as f32 / RATE;
                let envelope = if t % 0.5 < 0.12 { 0.8 } else { 0.12 };
                (2.0 * PI * 82.4 * t).sin() * envelope
            }),
        );
        let r = analyze_pcm(&input, &output).unwrap();
        assert!(r.frames > 100 && r.notes > 0);
        assert_eq!(&fs::read(&output).unwrap()[..8], b"CPULSE01");
        let _ = fs::remove_file(input);
        let _ = fs::remove_file(output);
    }

    #[test]
    fn layout_stays_compatible_and_appends_beat_grid() {
        let root = std::env::temp_dir();
        let input = root.join("soundwaive_layout.pcm");
        let output = root.join("soundwaive_layout.bin");
        write_pcm(&input, (0..(RATE * 20.0) as usize).map(groove));
        let r = analyze_pcm(&input, &output).unwrap();
        let b = fs::read(&output).unwrap();
        // Header exactly as Models.kt expects.
        assert_eq!(&b[..8], b"CPULSE01");
        assert_eq!(f32_at(&b, 16), r.bpm);
        assert_eq!(f32_at(&b, 20) as usize, r.frames);
        assert_eq!(f32_at(&b, 24), BANDS as f32);
        assert_eq!(f32_at(&b, 28), WAVES as f32);
        let mut at = 32 + r.frames * STRIDE * 4;
        assert_eq!(u32_at(&b, at), r.notes);
        at += 4 + r.notes * 16;
        let onsets = u32_at(&b, at);
        assert!(onsets >= 30, "{onsets} onsets");
        at += 4 + onsets * 8;
        assert_eq!(&b[at..at + 8], BEAT_MAGIC);
        let count = u32_at(&b, at + 8);
        assert_eq!(count, r.beats);
        assert_eq!(b.len(), at + 12 + count * 12);
        assert!((r.bpm - 120.0).abs() < 0.5, "{}", r.bpm);
        let beats: Vec<(f32, f32)> = (0..count)
            .map(|i| (f32_at(&b, at + 12 + i * 12), f32_at(&b, at + 20 + i * 12)))
            .collect();
        // Beats sit on the 0.5 s grid and downbeats on the accented bar starts.
        assert!(count >= 36);
        for (t, down) in &beats {
            let off = (t / 0.5 - (t / 0.5).round()).abs() * 0.5;
            // Within one analysis frame (23 ms).
            assert!(off < 0.02, "beat {t} off grid by {off}");
            if *down > 0.5 {
                assert!(
                    ((t / 2.0) - (t / 2.0).round()).abs() * 2.0 < 0.02,
                    "downbeat {t}"
                );
            }
        }
        // Every value in the frame table is finite and normalised.
        for i in 0..r.frames * STRIDE {
            let v = f32_at(&b, 32 + i * 4);
            assert!(v.is_finite());
        }
        let _ = fs::remove_file(input);
        let _ = fs::remove_file(output);
    }

    #[test]
    fn analysis_is_deterministic_and_handles_silence() {
        let root = std::env::temp_dir();
        let input = root.join("soundwaive_silence.pcm");
        let (o1, o2) = (
            root.join("soundwaive_s1.bin"),
            root.join("soundwaive_s2.bin"),
        );
        write_pcm(&input, (0..(RATE * 3.0) as usize).map(|_| 0.0));
        let r = analyze_pcm(&input, &o1).unwrap();
        analyze_pcm(&input, &o2).unwrap();
        assert_eq!(fs::read(&o1).unwrap(), fs::read(&o2).unwrap());
        assert_eq!(r.beats, 0);
        assert_eq!(r.bpm, 120.0);
        for p in [input, o1, o2] {
            let _ = fs::remove_file(p);
        }
    }

    #[test]
    fn shortest_accepted_input_does_not_panic() {
        let root = std::env::temp_dir();
        let input = root.join("soundwaive_short.pcm");
        let output = root.join("soundwaive_short.bin");
        write_pcm(&input, (0..1103).map(|i| (i as f32 * 0.3).sin() * 0.5));
        let r = analyze_pcm(&input, &output).unwrap();
        assert!(r.frames >= 4);
        let _ = fs::remove_file(input);
        let _ = fs::remove_file(output);
    }
}
