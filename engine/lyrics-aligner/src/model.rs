//! ONNX inference with tract (pure Rust). Graph A (`scores.onnx`) maps a magnitude
//! spectrogram (T,257) and one-hot phonemes (M,44) to alignment scores (T,M) plus the
//! encoder states. Graph B (`voice.onnx`, optional) is only needed for the VAD option.
use anyhow::{Context, Result};
use tract_onnx::prelude::*;

type Plan = std::sync::Arc<TypedSimplePlan>;

pub struct ScoresOut {
    pub scores: Vec<f32>, // (T, M) row-major
    pub x_enc: Vec<f32>,  // (T, 512)
    pub h_txt: Vec<f32>,  // (M, 512)
}

pub struct Model {
    scores: Plan,
    voice: Option<Plan>,
}

fn load(bytes: &[u8]) -> Result<Plan> {
    let m = tract_onnx::onnx()
        .model_for_read(&mut std::io::Cursor::new(bytes))?
        .into_optimized()?
        .into_runnable()?;
    Ok(m)
}

fn t2(v: Vec<f32>, r: usize, c: usize) -> Result<TValue> {
    Ok(tract_ndarray::Array2::from_shape_vec((r, c), v)?
        .into_tensor()
        .into_tvalue())
}

fn flat(t: &TValue) -> Result<Vec<f32>> {
    Ok(t.to_plain_array_view::<f32>()?.iter().copied().collect())
}

impl Model {
    pub fn from_bytes(scores_onnx: &[u8], voice_onnx: Option<&[u8]>) -> Result<Self> {
        Ok(Model {
            scores: load(scores_onnx).context("loading scores.onnx")?,
            voice: voice_onnx
                .map(load)
                .transpose()
                .context("loading voice.onnx")?,
        })
    }

    pub fn has_voice(&self) -> bool {
        self.voice.is_some()
    }

    pub fn scores(&self, spec: &[f32], frames: usize, phoneme_idx: &[usize]) -> Result<ScoresOut> {
        let m = phoneme_idx.len();
        let mut oh = vec![0f32; m * 44];
        for (i, &p) in phoneme_idx.iter().enumerate() {
            oh[i * 44 + p] = 1.0;
        }
        let out = self
            .scores
            .run(tvec!(t2(spec.to_vec(), frames, 257)?, t2(oh, m, 44)?))?;
        Ok(ScoresOut {
            scores: flat(&out[0])?,
            x_enc: flat(&out[1])?,
            h_txt: flat(&out[2])?,
        })
    }

    /// Per-frame magnitude of the model's singing-voice estimate (upstream `vocals_mag`).
    pub fn voice_mag(
        &self,
        s: &ScoresOut,
        alphas: Vec<f32>,
        spec: &[f32],
        frames: usize,
        m: usize,
    ) -> Result<Vec<f32>> {
        let plan = self
            .voice
            .as_ref()
            .context("voice.onnx not loaded (needed for VAD)")?;
        let out = plan.run(tvec!(
            t2(s.x_enc.clone(), frames, 512)?,
            t2(s.h_txt.clone(), m, 512)?,
            t2(alphas, frames, m)?,
            t2(spec.to_vec(), frames, 257)?
        ))?;
        flat(&out[0])
    }
}
