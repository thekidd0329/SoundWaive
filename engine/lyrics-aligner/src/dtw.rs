//! Alignment decoding, equivalent to upstream align.py `optimal_alignment_path`,
//! `compute_phoneme_onsets` and `compute_word_alignment`, and model.py `dtw_matrix` (for VAD).
//! Recurrence (monotonic, each frame either stays on phoneme m or advances by one):
//!   D[n][m] = S[n][m] + max(D[n-1][m], D[n-1][m-1]),  D[-1][-1] = init, other borders = -100000
//! Row-by-row evaluation gives the same values as upstream's diagonal sweep. Instead of keeping
//! the full float64 matrix we store one decision byte per cell; tie -> "stay", like np.argmax.
use anyhow::{bail, Result};

const NEG: f64 = -100000.0;

/// Returns phoneme index per frame (length N).
pub fn optimal_path(scores: &[f32], n: usize, m: usize, init: f64) -> Result<Vec<usize>> {
    if m == 0 || n == 0 {
        bail!("empty input");
    }
    if n < m {
        bail!("audio too short: {n} frames for {m} phoneme tokens");
    }
    let mut prev = vec![NEG; m];
    let mut cur = vec![0f64; m];
    let mut step = vec![0u8; n * m]; // step[n][m]=1 if best predecessor of (n,m) is (n-1,m-1)
    for i in 0..n {
        for j in 0..m {
            let stay = prev[j];
            let diag = if j == 0 {
                if i == 0 {
                    init
                } else {
                    NEG
                }
            } else {
                prev[j - 1]
            };
            let s = scores[i * m + j] as f64;
            if diag > stay {
                step[i * m + j] = 1;
                cur[j] = s + diag;
            } else {
                cur[j] = s + stay;
            }
        }
        std::mem::swap(&mut prev, &mut cur);
    }
    // backtracking exactly as upstream: last frame -> last phoneme, walk back while m > 0
    let mut path = vec![0usize; n];
    path[n - 1] = m - 1;
    let mut j = m - 1;
    let mut i: isize = n as isize - 2;
    while j > 0 {
        if i < 0 {
            bail!("DTW backward pass failed (more phonemes than frames can host)");
        }
        // upstream compares acc[i][j] vs acc[i][j-1]; that is the forward decision of cell (i+1, j)
        let adv = step[(i as usize + 1) * m + j] as usize;
        path[i as usize] = j - adv;
        j -= adv;
        i -= 1;
    }
    for k in 0..=(i.max(-1) as isize) {
        if k >= 0 {
            path[k as usize] = 0;
        }
    }
    Ok(path)
}

/// Upstream `compute_phoneme_onsets` (seconds), one onset per phoneme token.
pub fn phoneme_onsets(path: &[usize], hop: usize, sr: u32) -> Vec<f64> {
    let mut skipped = Vec::new();
    let mut on = vec![0.0f64];
    for k in 0..path.len().saturating_sub(1) {
        let (x, y) = (path[k], path[k + 1]);
        if x + 2 == y {
            skipped.push(x + 1);
        }
        if x != y {
            on.push((k + 1) as f64 * hop as f64 / sr as f64);
        }
    }
    for idx in skipped {
        let v = on[idx];
        on.insert(idx, v);
    }
    on
}

/// Upstream `compute_word_alignment`: (onsets, offsets) per word.
pub fn word_alignment(tokens: &[usize], on: &[f64], space: usize) -> (Vec<f64>, Vec<f64>) {
    let mut ons = Vec::new();
    let mut offs = Vec::new();
    for (idx, &p) in tokens.iter().enumerate() {
        if idx == 0 {
            ons.push(on[1]);
            continue;
        }
        if p == space && idx != tokens.len() - 1 {
            offs.push(on[idx]);
            ons.push(on[idx + 1]);
        }
    }
    offs.push(*on.last().unwrap());
    (ons, offs)
}

/// model.py `dtw_matrix(mode='faster')` (float32, init 200000) followed by softmax over
/// phonemes per frame: the soft attention `alphas` the separation branch consumes (VAD only).
pub fn dtw_softmax_alphas(scores: &[f32], n: usize, m: usize) -> Vec<f32> {
    let neg = -100000f32;
    let mut prev = vec![neg; m];
    let mut cur = vec![0f32; m];
    let mut out = vec![0f32; n * m];
    for i in 0..n {
        for j in 0..m {
            let diag = if j == 0 {
                if i == 0 {
                    200000f32
                } else {
                    neg
                }
            } else {
                prev[j - 1]
            };
            cur[j] = scores[i * m + j] + prev[j].max(diag);
        }
        let mx = cur.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
        let mut sum = 0f32;
        let row = &mut out[i * m..(i + 1) * m];
        for j in 0..m {
            row[j] = (cur[j] - mx).exp();
            sum += row[j];
        }
        for v in row.iter_mut() {
            *v /= sum;
        }
        std::mem::swap(&mut prev, &mut cur);
    }
    out
}
