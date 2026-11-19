//! Tempo estimation and beat/downbeat tracking on an onset-strength function.
//!
//! Tempo: autocorrelation of the high-passed detection function, scored with a
//! harmonic comb at fractional lags (0.05 BPM steps) and a log-normal tempo prior.
//! Beats: Ellis-style dynamic programming, which follows gradual tempo drift and locks
//! the phase to actual hits. Downbeats: the bar phase whose beats carry the most bass
//! attack and low-register harmonic change.
use crate::dsp::{moving_mean, parabolic, quantile};

pub const MIN_BPM: f32 = 55.0;
pub const MAX_BPM: f32 = 215.0;
const PRIOR_CENTER: f32 = 120.0;
/// Standard deviation of the tempo prior in octaves.
const PRIOR_OCTAVES: f32 = 1.0;
/// Penalty weight for deviating from the beat period between consecutive beats.
const TIGHTNESS: f32 = 100.0;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Beat {
    pub time: f32,
    pub strength: f32,
    pub downbeat: bool,
}

/// High-passed, non-negative copy of an onset function (removes slow loudness trends).
fn emphasise(odf: &[f32], fps: f32) -> Vec<f32> {
    let trend = moving_mean(odf, (0.4 * fps).round() as usize);
    odf.iter()
        .zip(&trend)
        .map(|(v, m)| (v - m).max(0.0))
        .collect()
}

fn autocorrelation(x: &[f32], max_lag: usize) -> Vec<f32> {
    let n = x.len();
    let mut r = vec![0.0f32; max_lag + 1];
    for (lag, slot) in r.iter_mut().enumerate() {
        if lag >= n {
            break;
        }
        let mut s = 0.0f64;
        for i in lag..n {
            s += (x[i] * x[i - lag]) as f64;
        }
        *slot = (s / (n - lag) as f64) as f32;
    }
    let r0 = r[0].max(1e-12);
    r.iter().map(|v| v / r0).collect()
}

fn interp(r: &[f32], lag: f32) -> f32 {
    let i = lag.floor() as usize;
    if i + 1 >= r.len() {
        return 0.0;
    }
    let f = lag - i as f32;
    r[i] * (1.0 - f) + r[i + 1] * f
}

/// Global tempo in BPM, or `None` when the signal has no periodic pulse to speak of.
pub fn tempo(odf: &[f32], fps: f32) -> Option<f32> {
    let x = emphasise(odf, fps);
    let max_lag = (fps * 60.0 / MIN_BPM * 4.0).ceil() as usize + 2;
    if x.len() < (fps * 60.0 / MIN_BPM * 2.0) as usize || x.iter().all(|&v| v <= 0.0) {
        return None;
    }
    let r = autocorrelation(&x, max_lag);
    let mut best = (f32::MIN, 0.0f32);
    let steps = ((MAX_BPM - MIN_BPM) / 0.05).round() as usize;
    for s in 0..=steps {
        let bpm = MIN_BPM + s as f32 * 0.05;
        let lag = fps * 60.0 / bpm;
        // Harmonic comb: a real period also correlates at 2x, 3x, 4x its lag.
        let mut score = 0.0;
        for (h, w) in [(1.0, 1.0), (2.0, 0.5), (3.0, 0.33), (4.0, 0.25)] {
            score += w * interp(&r, lag * h);
        }
        let octaves = (bpm / PRIOR_CENTER).log2() / PRIOR_OCTAVES;
        score *= (-0.5 * octaves * octaves).exp();
        if score > best.0 {
            best = (score, bpm);
        }
    }
    (best.0 > 0.0).then_some(best.1)
}

/// Dynamic-programming beat tracker. Returns beat frames (integer) on `odf`'s grid.
fn track_frames(odf: &[f32], fps: f32, bpm: f32) -> Vec<usize> {
    let n = odf.len();
    let period = fps * 60.0 / bpm;
    if n < (2.0 * period) as usize {
        return Vec::new();
    }
    let mean = odf.iter().sum::<f32>() / n as f32;
    let sd = (odf.iter().map(|v| (v - mean).powi(2)).sum::<f32>() / n as f32)
        .sqrt()
        .max(1e-9);
    // Light smoothing (~period/32 Gaussian is about one frame here) and normalisation.
    let local: Vec<f32> = (0..n)
        .map(|i| {
            let a = odf[i.saturating_sub(1)];
            let c = odf[(i + 1).min(n - 1)];
            (0.25 * a + 0.5 * odf[i] + 0.25 * c) / sd
        })
        .collect();
    let lo = (period * 0.5).round().max(1.0) as usize;
    let hi = (period * 2.0).round() as usize;
    let mut cum = vec![0.0f32; n];
    let mut back = vec![usize::MAX; n];
    for t in 0..n {
        let mut best = f32::MIN;
        let mut arg = usize::MAX;
        if t >= lo {
            for prev in t.saturating_sub(hi)..=t - lo {
                let ratio = (t - prev) as f32 / period;
                let v = cum[prev] - TIGHTNESS * ratio.ln().powi(2);
                if v > best {
                    best = v;
                    arg = prev;
                }
            }
        }
        if arg != usize::MAX && best > 0.0 {
            cum[t] = local[t] + best;
            back[t] = arg;
        } else {
            cum[t] = local[t];
        }
    }
    // Last beat: the final local maximum of the cumulative score that is still strong.
    let maxima: Vec<usize> = (1..n - 1)
        .filter(|&i| cum[i] > cum[i - 1] && cum[i] >= cum[i + 1])
        .collect();
    if maxima.is_empty() {
        return Vec::new();
    }
    let peaks: Vec<f32> = maxima.iter().map(|&i| cum[i]).collect();
    let floor = 0.5 * quantile(&peaks, 0.5);
    let mut t = *maxima.iter().rev().find(|&&i| cum[i] >= floor).unwrap();
    let mut beats = vec![t];
    while back[t] != usize::MAX {
        t = back[t];
        beats.push(t);
    }
    beats.reverse();
    // Trim beats extrapolated into silence or fade-in/out at either end.
    let rms = (local.iter().map(|v| v * v).sum::<f32>() / n as f32).sqrt();
    let strength = |b: usize| {
        let a = b.saturating_sub((period * 0.5) as usize);
        let z = (b + (period * 0.5) as usize + 1).min(n);
        local[a..z].iter().cloned().fold(0.0, f32::max)
    };
    while beats.first().is_some_and(|&b| strength(b) < 0.5 * rms) {
        beats.remove(0);
    }
    while beats.last().is_some_and(|&b| strength(b) < 0.5 * rms) {
        beats.pop();
    }
    beats
}

/// Least-squares beat period in seconds (robust to a few late/early beats).
fn fitted_bpm(times: &[f32]) -> Option<f32> {
    if times.len() < 4 {
        return None;
    }
    let ibis: Vec<f32> = times.windows(2).map(|w| w[1] - w[0]).collect();
    let med = quantile(&ibis, 0.5);
    // Only fit steady stretches: if the median spacing explains the span, use the slope.
    let n = times.len() as f32;
    let mx = (n - 1.0) / 2.0;
    let my = times.iter().sum::<f32>() / n;
    let (mut sxy, mut sxx) = (0.0f64, 0.0f64);
    for (i, &t) in times.iter().enumerate() {
        sxy += ((i as f32 - mx) * (t - my)) as f64;
        sxx += ((i as f32 - mx).powi(2)) as f64;
    }
    let slope = (sxy / sxx) as f32;
    let period = if (slope - med).abs() < 0.03 * med {
        slope
    } else {
        med
    };
    (period > 0.0).then(|| 60.0 / period)
}

pub struct BeatGrid {
    pub bpm: f32,
    pub beats: Vec<Beat>,
}

/// Tracks beats on `full` (kick + body onset strength), snaps each beat to a nearby
/// detected onset for sub-frame accuracy, then chooses the bar phase from `bass_attack`
/// (low-band onset function) and beat-synchronous change of `bass_bands`
/// (frames x `width` bass-register log spectrum: bass-note and chord changes).
#[allow(clippy::too_many_arguments)]
pub fn grid(
    full: &[f32],
    bass_attack: &[f32],
    bass_bands: &[f32],
    width: usize,
    onset_times: &[f32],
    fps: f32,
    latency: f32,
) -> Option<BeatGrid> {
    let bpm0 = tempo(full, fps)?;
    let frames = track_frames(full, fps, bpm0);
    if frames.len() < 4 {
        return None;
    }
    let level = quantile(full, 0.99).max(1e-9);
    let mut beats: Vec<Beat> = frames
        .iter()
        .map(|&f| {
            let raw = (f as f32 + parabolic(full, f)) / fps + latency;
            // Prefer the exact onset time when a detected onset is within 35 ms.
            let snapped = onset_times
                .iter()
                .copied()
                .filter(|t| (t - raw).abs() <= 0.035)
                .min_by(|a, b| (a - raw).abs().total_cmp(&(b - raw).abs()))
                .unwrap_or(raw);
            Beat {
                time: snapped.max(0.0),
                strength: (full[f] / level).clamp(0.0, 1.0),
                downbeat: false,
            }
        })
        .collect();
    let times: Vec<f32> = beats.iter().map(|b| b.time).collect();
    let bpm = fitted_bpm(&times).unwrap_or(bpm0);
    let phase = downbeat_phase(&frames, bass_attack, bass_bands, width, fps * 60.0 / bpm);
    for (i, b) in beats.iter_mut().enumerate() {
        b.downbeat = i % 4 == phase;
    }
    Some(BeatGrid { bpm, beats })
}

fn window_max(v: &[f32], center: usize, half: usize) -> f32 {
    let a = center.saturating_sub(half);
    let b = (center + half + 1).min(v.len());
    v[a..b].iter().cloned().fold(0.0, f32::max)
}

/// Mean spectrum of frames `a..b` (clamped) from a frames x `width` matrix.
fn mean_rows(m: &[f32], width: usize, a: usize, b: usize) -> Vec<f32> {
    let rows = m.len() / width.max(1);
    let (a, b) = (a.min(rows), b.min(rows));
    let mut out = vec![0.0f32; width];
    if b <= a {
        return out;
    }
    for r in a..b {
        for (o, v) in out.iter_mut().zip(&m[r * width..(r + 1) * width]) {
            *o += v;
        }
    }
    out.iter_mut().for_each(|o| *o /= (b - a) as f32);
    out
}

/// Bar phase (0..4) whose beats start new bass notes/chords and carry the strongest
/// bass attacks. Harmony compares the beat-long spans after and before each beat, so a
/// bass line that repeats a note on every beat does not look like a bar start.
fn downbeat_phase(
    frames: &[usize],
    bass_attack: &[f32],
    bass_bands: &[f32],
    width: usize,
    period: f32,
) -> usize {
    let bass_level = quantile(bass_attack, 0.99).max(1e-9);
    let reach = ((period * 0.15).round() as usize).max(1);
    let change: Vec<f32> = (0..frames.len())
        .map(|i| {
            if i == 0 || i + 1 >= frames.len() || width == 0 {
                return 0.0;
            }
            let before = mean_rows(bass_bands, width, frames[i - 1], frames[i]);
            let after = mean_rows(bass_bands, width, frames[i], frames[i + 1]);
            before.iter().zip(&after).map(|(x, y)| (x - y).abs()).sum()
        })
        .collect();
    let change_level = quantile(&change, 0.95).max(1e-9);
    let mut score = [0.0f32; 4];
    let mut count = [0usize; 4];
    for (i, &f) in frames.iter().enumerate() {
        let k = i % 4;
        score[k] += change[i] / change_level + 0.5 * window_max(bass_attack, f, reach) / bass_level;
        count[k] += 1;
    }
    (0..4)
        .max_by(|&a, &b| {
            (score[a] / count[a].max(1) as f32).total_cmp(&(score[b] / count[b].max(1) as f32))
        })
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pulses(fps: f32, bpm: f32, seconds: f32, offset: f32) -> (Vec<f32>, Vec<f32>) {
        let n = (seconds * fps) as usize;
        let mut v = vec![0.0f32; n];
        let mut truth = Vec::new();
        let mut t = offset;
        while t < seconds - 0.1 {
            let pos = t * fps;
            let i = pos.floor() as usize;
            let f = pos - i as f32;
            if i + 1 < n {
                v[i] += 1.0 - f;
                v[i + 1] += f;
            }
            truth.push(t);
            t += 60.0 / bpm;
        }
        (v, truth)
    }

    #[test]
    fn beat_train_detects_120() {
        let fps = 43.0664;
        let (v, _) = pulses(fps, 120.0, 100.0, 0.0);
        assert!((tempo(&v, fps).unwrap() - 120.0).abs() < 0.5);
    }

    #[test]
    fn fractional_tempo_is_resolved() {
        // The old integer-lag search could only answer 121 or 123 here.
        let fps = 43.0664;
        let (v, truth) = pulses(fps, 123.4, 60.0, 1.37);
        let g = grid(&v, &v, &[], 0, &truth, fps, 0.0).unwrap();
        assert!((g.bpm - 123.4).abs() < 0.1, "{}", g.bpm);
        // Phase locks to the hits after the silent intro.
        assert!((g.beats[0].time - 1.37).abs() < 0.03, "{:?}", g.beats[0]);
        let hits = g
            .beats
            .iter()
            .filter(|b| truth.iter().any(|t| (t - b.time).abs() < 0.02))
            .count();
        assert!(hits as f32 >= 0.97 * truth.len() as f32);
    }

    #[test]
    fn downbeat_follows_bass_accent() {
        let fps = 43.0664;
        let (v, truth) = pulses(fps, 100.0, 40.0, 0.5);
        // Bass attacks on every 4th beat starting with the 2nd pulse.
        let mut bass = vec![0.0f32; v.len()];
        for (i, t) in truth.iter().enumerate() {
            if i % 4 == 1 {
                bass[(t * fps).round() as usize] = 1.0;
            }
        }
        let g = grid(&v, &bass, &[], 0, &truth, fps, 0.0).unwrap();
        for b in g.beats.iter().filter(|b| b.downbeat) {
            let idx = truth
                .iter()
                .position(|t| (t - b.time).abs() < 0.03)
                .unwrap();
            assert_eq!(idx % 4, 1);
        }
    }

    #[test]
    fn silence_and_short_input_have_no_grid() {
        assert!(grid(&[0.0; 5000], &[0.0; 5000], &[], 0, &[], 43.0, 0.0).is_none());
        assert!(grid(&[1.0, 0.0, 0.5], &[0.0; 3], &[], 0, &[], 43.0, 0.0).is_none());
    }
}
