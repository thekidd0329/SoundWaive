//! Word-timed lyrics: the JNI-facing side of the `lyrics-aligner` crate (an in-repo Rust
//! port of schufo/lyrics-aligner, MIT). Fully offline. The two ONNX models and the CMU
//! dictionary are app assets that Kotlin copies to app storage once and passes in by path,
//! so none of them is compiled into `libcrownpulse_engine.so`.
//!
//! Built only with the `lyrics` cargo feature (on by default for every Android ABI). Without
//! it, [available] is false and [align_json] returns an error, and the app hides the feature.

/// One word of the user's lyrics as it is shown on screen, with punctuation and case kept.
/// `tokenize` in the aligner lowercases and strips everything but letters, digits and
/// apostrophes, so this maps each aligned word back to the text the user typed.
pub fn display_words(lyrics: &str) -> Vec<(String, usize)> {
    let mut out: Vec<(String, usize)> = Vec::new();
    let mut line_no = 0;
    for line in lyrics.lines() {
        let mut any = false;
        let mut pending = String::new(); // punctuation-only pieces before the first word
        for piece in line.split(' ') {
            if piece.is_empty() {
                continue;
            }
            let clean = piece
                .to_lowercase()
                .replace('\u{2019}', "'")
                .chars()
                .any(|c| c.is_alphanumeric() || c == '\'');
            if clean {
                let mut text = std::mem::take(&mut pending);
                if !text.is_empty() {
                    text.push(' ');
                }
                text.push_str(piece);
                out.push((text, line_no));
                any = true;
            } else if any {
                // "word —" or "word !": keep it with the word before.
                let last = out.last_mut().expect("a word on this line");
                last.0.push(' ');
                last.0.push_str(piece);
            } else {
                if !pending.is_empty() {
                    pending.push(' ');
                }
                pending.push_str(piece);
            }
        }
        if any {
            line_no += 1;
        }
    }
    out
}

/// Interleaved little-endian 16-bit PCM (the decoder's `audio.pcm`) mixed down to mono f32.
pub fn mono_from_pcm16(bytes: &[u8], channels: usize) -> Vec<f32> {
    let channels = channels.max(1);
    let frame = 2 * channels;
    bytes
        .chunks_exact(frame)
        .map(|f| {
            let mut sum = 0.0f32;
            for c in 0..channels {
                sum += i16::from_le_bytes([f[2 * c], f[2 * c + 1]]) as f32;
            }
            sum / (channels as f32 * 32768.0)
        })
        .collect()
}

/// Whether this build can align lyrics.
pub fn available() -> bool {
    cfg!(feature = "lyrics")
}

#[cfg(feature = "lyrics")]
mod imp {
    use super::{display_words, mono_from_pcm16};
    use lyrics_aligner::{g2p, Aligner, Options};
    use std::path::Path;
    use std::sync::Mutex;

    /// The loaded models, kept between alignments (loading takes a second or two).
    static ALIGNER: Mutex<Option<Aligner>> = Mutex::new(None);

    pub struct Request<'a> {
        pub pcm: &'a Path,
        pub sample_rate: u32,
        pub channels: usize,
        pub lyrics: &'a str,
        /// Folder holding `scores.onnx`, `voice.onnx` and `cmudict-nostress.txt.gz`.
        pub model_dir: &'a Path,
        /// Upstream `--vad-threshold`; 0 = off (no voice model needed).
        pub vad_threshold: f32,
        /// Re-align on an 8 ms shifted copy and flag words that move (twice the time).
        pub stability: bool,
    }

    pub fn align(r: &Request) -> anyhow::Result<String> {
        anyhow::ensure!(
            r.sample_rate >= 8000,
            "unsupported sample rate {}",
            r.sample_rate
        );
        if !g2p::has_cmudict() {
            g2p::init_cmudict(&std::fs::read(r.model_dir.join("cmudict-nostress.txt.gz"))?)?;
        }
        let want_voice = r.vad_threshold > 0.0;
        let mut guard = ALIGNER.lock().unwrap_or_else(|e| e.into_inner());
        if guard.as_ref().is_none_or(|a| want_voice && !a.has_voice()) {
            *guard = None; // free the old models before loading the new ones
            let scores = r.model_dir.join("scores.onnx");
            let voice = r.model_dir.join("voice.onnx");
            let voice = if want_voice {
                Some(voice.to_string_lossy().into_owned())
            } else {
                None
            };
            *guard = Some(Aligner::from_files(
                &scores.to_string_lossy(),
                voice.as_deref(),
            )?);
        }
        let aligner = guard.as_ref().expect("aligner loaded");
        let audio = mono_from_pcm16(&std::fs::read(r.pcm)?, r.channels);
        anyhow::ensure!(
            audio.len() > r.sample_rate as usize,
            "the song is too short to align"
        );
        let opt = Options {
            vad_threshold: r.vad_threshold,
            stability_check: r.stability,
            ..Options::default()
        };
        let words = aligner.align(&audio, r.sample_rate, r.lyrics, &opt)?;
        let shown = display_words(r.lyrics);
        anyhow::ensure!(
            shown.len() == words.len(),
            "lyrics word count mismatch ({} shown, {} aligned)",
            shown.len(),
            words.len()
        );
        let list: Vec<serde_json::Value> = words
            .iter()
            .zip(shown)
            .map(|(w, (text, line))| {
                serde_json::json!({
                    "text": text,
                    "word": w.word,
                    "line": line,
                    "onset": w.onset,
                    "offset": w.offset,
                    "confidence": w.confidence,
                    "flagged": w.flagged,
                    "g2p": w.g2p,
                    "shiftMs": w.shift_ms,
                })
            })
            .collect();
        Ok(serde_json::json!({ "words": list, "vad": want_voice }).to_string())
    }
}

#[cfg(feature = "lyrics")]
pub use imp::{align, Request};

/// JSON for Kotlin: `{"words":[...]}` or `{"error":"..."}`.
#[cfg(feature = "lyrics")]
pub fn align_json(r: &Request) -> String {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| align(r))) {
        Ok(Ok(json)) => json,
        Ok(Err(e)) => serde_json::json!({ "error": e.to_string() }).to_string(),
        Err(_) => {
            serde_json::json!({ "error": "the lyrics aligner stopped unexpectedly" }).to_string()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_words_follow_the_aligner_tokens() {
        let text =
            "If you hear me now\n\n— Tap the glass, tap the glass!\nCheap-shot skies …\n???\n";
        let shown = display_words(text);
        let words: Vec<&str> = shown.iter().map(|(w, _)| w.as_str()).collect();
        assert_eq!(
            words,
            [
                "If",
                "you",
                "hear",
                "me",
                "now",
                "— Tap",
                "the",
                "glass,",
                "tap",
                "the",
                "glass!",
                "Cheap-shot",
                "skies …"
            ]
        );
        let lines: Vec<usize> = shown.iter().map(|(_, l)| *l).collect();
        assert_eq!(lines, [0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 2, 2]);
    }

    #[test]
    fn pcm_mixes_to_mono() {
        let mut b = Vec::new();
        for v in [16384i16, -16384, 32767, 32767] {
            b.extend_from_slice(&v.to_le_bytes());
        }
        let m = mono_from_pcm16(&b, 2);
        assert_eq!(m.len(), 2);
        assert!(m[0].abs() < 1e-6);
        assert!((m[1] - 32767.0 / 32768.0).abs() < 1e-6);
        assert_eq!(mono_from_pcm16(&b, 1).len(), 4);
    }

    #[cfg(feature = "lyrics")]
    #[test]
    fn display_words_match_the_aligner_on_odd_input() {
        // Same count as the aligner's tokenizer for punctuation, hyphens and blank lines.
        for text in [
            "Tap the glass, tap the glass\n\nCheap-shot skies!",
            "  leading  spaces\t and tabs\n'quoted' words\n...\nrock 'n' roll — 2 times",
            "don’t stop / believin'",
        ] {
            let t = lyrics_aligner::g2p::tokenize(text);
            let d = display_words(text);
            assert_eq!(t.len(), d.len(), "{text}");
            for ((_, a), (_, b)) in t.iter().zip(d.iter()) {
                assert_eq!(a, b, "{text}");
            }
        }
    }
}
