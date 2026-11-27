//! Rust port of [schufo/lyrics-aligner](https://github.com/schufo/lyrics-aligner)
//! (Schulze-Forster, Doire, Richard, Badeau, "Phoneme Level Lyrics Alignment and
//! Text-Informed Singing Voice Separation", IEEE/ACM TASLP 2021). MIT licensed, see LICENSE.
//!
//!
//! Vendored into SoundWaive from the standalone port: the models (`scores.onnx`,
//! `voice.onnx`) and the CMU dictionary are app assets passed in at runtime, so nothing
//! large is compiled into the native library. See `engine/src/lyrics.rs` for the JNI entry.
pub mod dtw;
pub mod features;
pub mod g2p;
pub mod model;
pub mod output;

use anyhow::{bail, Context, Result};
use serde::Serialize;
use std::collections::HashMap;

pub use g2p::G2pSource;

#[derive(Debug, Clone, Serialize)]
pub struct PhonemeTiming {
    pub phoneme: String,
    pub onset: f64,
}

#[derive(Debug, Clone, Serialize)]
pub struct WordTiming {
    pub word: String,
    /// 0-based index of the (non-empty) lyric line the word came from
    pub line: usize,
    pub onset: f64,
    pub offset: f64,
    /// Heuristic 0..1: mean local posterior of the chosen phonemes along the path. Not calibrated.
    pub confidence: f32,
    /// true if confidence is low, the duration looks implausible, or the pronunciation was guessed
    pub flagged: bool,
    pub g2p: G2pSource,
    /// With `Options::stability_check`: how far (ms) the onset moved when the input was shifted
    /// by half a hop (8 ms) and re-aligned. Large values mean the alignment there is ambiguous.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub shift_ms: Option<f64>,
    pub phonemes: Vec<PhonemeTiming>,
}

#[derive(Debug, Clone)]
pub struct Options {
    /// Upstream `--vad-threshold`; 0 disables (default). Needs voice.onnx.
    pub vad_threshold: f32,
    /// DTW init value at (0,0); align.py uses 200.
    pub dtw_init: f64,
    pub overrides: HashMap<String, String>,
    pub confidence_threshold: f32,
    /// Re-run on an 8 ms shifted copy and flag words whose onset moves > 50 ms (2x cost).
    pub stability_check: bool,
}

impl Default for Options {
    fn default() -> Self {
        Options {
            vad_threshold: 0.0,
            dtw_init: 200.0,
            overrides: HashMap::new(),
            confidence_threshold: 0.2,
            stability_check: false,
        }
    }
}

/// Largest frames x tokens alignment (about 190 MB for scores and decisions, more with VAD).
pub const MAX_CELLS: usize = 48_000_000;

pub struct Aligner {
    model: model::Model,
}

/// Debug/parity intermediates.
pub struct Detail {
    pub audio16k: Vec<f32>,
    pub spec: Vec<f32>,
    pub frames: usize,
    pub tokens: Vec<usize>,
    pub scores: Vec<f32>,
    pub phoneme_onsets: Vec<f64>,
    pub words: Vec<WordTiming>,
}

impl Aligner {
    pub fn from_bytes(scores_onnx: &[u8], voice_onnx: Option<&[u8]>) -> Result<Self> {
        Ok(Aligner {
            model: model::Model::from_bytes(scores_onnx, voice_onnx)?,
        })
    }
    /// True when the voice model is loaded, so `Options::vad_threshold` can be used.
    pub fn has_voice(&self) -> bool {
        self.model.has_voice()
    }
    pub fn from_files(scores_onnx: &str, voice_onnx: Option<&str>) -> Result<Self> {
        let s = std::fs::read(scores_onnx).with_context(|| scores_onnx.to_string())?;
        let v = voice_onnx.map(std::fs::read).transpose()?;
        Self::from_bytes(&s, v.as_deref())
    }

    pub fn align(
        &self,
        audio_mono: &[f32],
        sample_rate: u32,
        lyrics: &str,
        opt: &Options,
    ) -> Result<Vec<WordTiming>> {
        Ok(self
            .align_detail(audio_mono, sample_rate, lyrics, opt)?
            .words)
    }

    pub fn align_detail(
        &self,
        audio_mono: &[f32],
        sample_rate: u32,
        lyrics: &str,
        opt: &Options,
    ) -> Result<Detail> {
        let audio16k = features::resample_to_16k(audio_mono, sample_rate);
        let mut d = self.align_16k(audio16k, lyrics, opt)?;
        if opt.stability_check {
            let shift = features::HOP / 2;
            let mut shifted = vec![0f32; shift];
            shifted.extend_from_slice(&d.audio16k);
            let d2 = self.align_16k(shifted, lyrics, opt)?;
            let dt = shift as f64 / features::SAMPLE_RATE as f64;
            for (w, w2) in d.words.iter_mut().zip(d2.words.iter()) {
                let ms = ((w2.onset - dt) - w.onset).abs() * 1000.0;
                w.shift_ms = Some((ms * 10.0).round() / 10.0);
                if ms > 50.0 {
                    w.flagged = true;
                }
            }
        }
        Ok(d)
    }

    fn align_16k(&self, audio16k: Vec<f32>, lyrics: &str, opt: &Options) -> Result<Detail> {
        if !g2p::has_cmudict() {
            bail!("pronunciation dictionary not loaded (call g2p::init_cmudict first)");
        }
        let words = g2p::words_to_phonemes(lyrics, &opt.overrides);
        if words.is_empty() {
            bail!("no words in lyrics");
        }
        // token sequence: > w1 phonemes > w2 phonemes > ... >
        let mut tokens = vec![g2p::SPACE];
        let mut tok_word = vec![usize::MAX];
        for (wi, w) in words.iter().enumerate() {
            for p in &w.phonemes {
                tokens.push(
                    g2p::phoneme_index(p)
                        .with_context(|| format!("unknown phoneme {p} in '{}'", w.word))?,
                );
                tok_word.push(wi);
            }
            tokens.push(g2p::SPACE);
            tok_word.push(usize::MAX);
        }
        let (spec, frames) = features::magnitude_spectrogram(&audio16k);
        let m = tokens.len();
        // The score matrix (and the VAD attention) is frames x tokens floats. Refuse sizes
        // that would need hundreds of MB on a phone instead of being killed mid-way.
        if frames.saturating_mul(m) > MAX_CELLS {
            bail!(
                "this song and lyrics are too long to align in one go ({:.0} s, {} sounds); trim the song or the lyrics",
                frames as f64 * features::HOP as f64 / features::SAMPLE_RATE as f64,
                m
            );
        }
        if frames < m {
            bail!("audio too short ({frames} frames) for {m} phoneme tokens");
        }
        let out = self.model.scores(&spec, frames, &tokens)?;
        let mut scores = out.scores.clone();
        if opt.vad_threshold > 0.0 {
            let alphas = dtw::dtw_softmax_alphas(&out.scores, frames, m);
            let vm = self.model.voice_mag(&out, alphas, &spec, frames, m)?;
            let mx = scores.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
            for (n, v) in vm.iter().enumerate() {
                if *v < opt.vad_threshold {
                    for (j, &t) in tokens.iter().enumerate() {
                        if t == g2p::SPACE {
                            scores[n * m + j] = mx;
                        }
                    }
                }
            }
        }
        let path = dtw::optimal_path(&scores, frames, m, opt.dtw_init)?;
        let p_on = dtw::phoneme_onsets(&path, features::HOP, features::SAMPLE_RATE);
        if p_on.len() != m {
            bail!("alignment produced {} onsets for {m} tokens", p_on.len());
        }
        let (w_on, w_off) = dtw::word_alignment(&tokens, &p_on, g2p::SPACE);

        // confidence: local softmax (±8 tokens) of the chosen token's score, averaged per word
        let mut conf_sum = vec![0f32; words.len()];
        let mut conf_n = vec![0u32; words.len()];
        for (n, &j) in path.iter().enumerate() {
            let wi = tok_word[j];
            if wi == usize::MAX {
                continue;
            }
            let lo = j.saturating_sub(8);
            let hi = (j + 8).min(m - 1);
            let row = &out.scores[n * m..(n + 1) * m];
            let mx = row[lo..=hi]
                .iter()
                .cloned()
                .fold(f32::NEG_INFINITY, f32::max);
            let z: f32 = row[lo..=hi].iter().map(|v| (v - mx).exp()).sum();
            conf_sum[wi] += (row[j] - mx).exp() / z;
            conf_n[wi] += 1;
        }
        let mut result = Vec::with_capacity(words.len());
        let mut tok = 1;
        for (wi, w) in words.iter().enumerate() {
            let phs: Vec<PhonemeTiming> = w
                .phonemes
                .iter()
                .enumerate()
                .map(|(k, p)| PhonemeTiming {
                    phoneme: p.clone(),
                    onset: p_on[tok + k],
                })
                .collect();
            tok += w.phonemes.len() + 1;
            let conf = if conf_n[wi] > 0 {
                conf_sum[wi] / conf_n[wi] as f32
            } else {
                0.0
            };
            let dur = w_off[wi] - w_on[wi];
            let np = w.phonemes.len() as f64;
            // short function words ("a", "the") legitimately score low, so confidence only
            // flags words with 3+ phonemes
            let flagged = (conf < opt.confidence_threshold && w.phonemes.len() >= 3)
                || conf < 0.03
                || w.source == G2pSource::Rules
                || dur < 0.03 * np
                || dur > 2.5 + 0.5 * np;
            result.push(WordTiming {
                word: w.word.clone(),
                line: w.line,
                onset: w_on[wi],
                offset: w_off[wi],
                confidence: conf,
                flagged,
                g2p: w.source,
                shift_ms: None,
                phonemes: phs,
            });
        }
        Ok(Detail {
            audio16k,
            spec,
            frames,
            tokens,
            scores: out.scores,
            phoneme_onsets: p_on,
            words: result,
        })
    }
}
