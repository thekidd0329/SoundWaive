//! Onset detection: band-wise log-magnitude spectral flux on a short window, with
//! vibrato-suppressing maximum filtering (SuperFlux-style), offline adaptive
//! thresholds and sub-frame peak timing.
//!
//! The long 2048-sample analysis window (186 ms at 11025 Hz) is excellent for pitch but
//! smears attacks. Onsets therefore use their own 512-sample (46 ms) window on the same
//! hop grid, so every output stays aligned with frame `f` = time `f * HOP / RATE`.
use crate::dsp::{fft, hann, load_frame, moving_mean, parabolic, quantile, Complex};

pub const SHORT_N: usize = 512;
/// Log compression gain for magnitudes normalised by the window sum.
const GAMMA: f32 = 1000.0;
/// Upper bin edges (exclusive) of the three detection bands at 11025 Hz / 512:
/// kick and bass < 172 Hz, body/snare < 2.0 kHz, hats/air above.
const LOW_END: usize = 8;
const MID_END: usize = 93;
/// Brings linear bass-band flux to the scale of the log bands (full-scale sine ~ 1.0 -> 20).
const LOW_GAIN: f32 = 20.0;

/// Added to raw log-flux peak times (body, air and pulse functions). Log flux peaks as
/// soon as an attack enters the window's leading half. Calibrated with
/// `tools/eval_sync.py`; `tests::calibrated_click_timing` guards the bass value.
pub const LATENCY: f32 = 0.0;
/// Linear bass flux peaks later, while a kick's energy is still building. The lag depends
/// on the kick's shape: about 7 ms for the unit-test kick and 12 ms for the
/// `tools/eval_sync.py` kicks; this value splits the difference.
pub const BASS_LATENCY: f32 = -0.010;

#[derive(Clone, Debug)]
pub struct Detection {
    pub low: Vec<f32>,
    pub mid: Vec<f32>,
    pub high: Vec<f32>,
    /// Log-compressed bass flux: every bass-register attack counts, used for the pulse.
    pub low_log: Vec<f32>,
    /// Body/snare attacks with the part explained by simultaneous kicks removed.
    pub snare: Vec<f32>,
    /// Kick + body attacks (no hats), the pulse used for tempo and beat tracking.
    pub pulse: Vec<f32>,
}

pub struct ShortSpectrum {
    window: Vec<f32>,
    buf: Vec<Complex>,
    prev: Vec<f32>,
    cur: Vec<f32>,
    prev_lin: Vec<f32>,
    cur_lin: Vec<f32>,
}

impl Default for ShortSpectrum {
    fn default() -> Self {
        Self::new()
    }
}

impl ShortSpectrum {
    pub fn new() -> Self {
        Self {
            window: hann(SHORT_N),
            buf: vec![Complex::default(); SHORT_N],
            prev: vec![0.0; SHORT_N / 2 + 1],
            cur: vec![0.0; SHORT_N / 2 + 1],
            prev_lin: vec![0.0; LOW_END],
            cur_lin: vec![0.0; LOW_END],
        }
    }

    /// Positive spectral flux of the frame centred on `center`, as
    /// (low linear, mid log, high log, low log).
    pub fn flux(&mut self, pcm: &[f32], center: usize) -> [f32; 4] {
        load_frame(pcm, center, &self.window, &mut self.buf);
        fft(&mut self.buf);
        let scale = 2.0 / (SHORT_N as f32 * 0.5);
        for k in 0..=SHORT_N / 2 {
            let m = self.buf[k].norm() * scale;
            self.cur[k] = (1.0 + GAMMA * m).ln();
            if k < LOW_END {
                self.cur_lin[k] = m;
            }
        }
        let mut out = [0.0f32; 4];
        let last = SHORT_N / 2;
        for k in 1..last {
            // Bass register: linear magnitude, bin to bin. Log compression would make a
            // quiet bass note's attack as large as a kick's; bins are 21.5 Hz wide, so a
            // neighbour there is a different note rather than vibrato.
            // Above it: log magnitude against the louder neighbour of the previous frame,
            // so gliding tones (vibrato) do not register as new attacks.
            if k < LOW_END {
                out[0] += LOW_GAIN * (self.cur_lin[k] - self.prev_lin[k]).max(0.0);
                out[3] += (self.cur[k] - self.prev[k]).max(0.0);
            } else {
                let reference = self.prev[k - 1].max(self.prev[k]).max(self.prev[k + 1]);
                out[if k < MID_END { 1 } else { 2 }] += (self.cur[k] - reference).max(0.0);
            }
        }
        std::mem::swap(&mut self.prev, &mut self.cur);
        std::mem::swap(&mut self.prev_lin, &mut self.cur_lin);
        out
    }
}

impl Detection {
    pub fn with_capacity(frames: usize) -> Self {
        Self {
            low: Vec::with_capacity(frames),
            mid: Vec::with_capacity(frames),
            high: Vec::with_capacity(frames),
            low_log: Vec::with_capacity(frames),
            snare: Vec::new(),
            pulse: Vec::new(),
        }
    }

    pub fn push(&mut self, f: [f32; 4]) {
        self.low.push(f[0]);
        self.mid.push(f[1]);
        self.high.push(f[2]);
        self.low_log.push(f[3]);
    }

    /// Builds the derived detection functions once all frames are pushed.
    pub fn finish(&mut self) {
        let (sl, sm, sh) = (scale(&self.low), scale(&self.mid), scale(&self.high));
        // Snares are broadband; kicks are mostly bass and hats mostly air. Remove the
        // part of the body flux that those explain.
        self.snare = (0..self.low.len())
            .map(|i| {
                sm * (self.mid[i] / sm - 0.7 * self.low[i] / sl - 0.4 * self.high[i] / sh).max(0.0)
            })
            .collect();
        let sll = scale(&self.low_log);
        self.pulse = (0..self.low.len())
            .map(|i| self.low_log[i] / sll + self.mid[i] / sm)
            .collect();
    }
}

/// Robust peak level, never below an absolute floor so silence and steady tones,
/// whose flux is only rounding noise, cannot be stretched into fake attacks.
fn scale(v: &[f32]) -> f32 {
    quantile(v, 0.99).max(ABS_FLOOR)
}

/// Smallest log-flux level (summed over a band) treated as a real attack.
const ABS_FLOOR: f32 = 0.5;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Peak {
    pub frame: usize,
    /// Latency-corrected, sub-frame time in seconds.
    pub time: f32,
    /// Height relative to the song's robust peak level (keeps loud/quiet dynamics).
    pub strength: f32,
    /// Height relative to the surrounding ~4 s (adaptive whitening), used for gating.
    pub local: f32,
}

#[derive(Clone, Copy, Debug)]
pub struct PickParams {
    /// Minimum spacing between accepted onsets, in seconds.
    pub min_gap: f32,
    /// Absolute floor above the local mean, relative to the robust peak level.
    pub delta: f32,
    /// Multiple of the long-window median a peak must exceed.
    pub lambda: f32,
    /// Seconds added to raw peak times (see `LATENCY`).
    pub latency: f32,
}

impl PickParams {
    pub const KICK: Self = Self {
        min_gap: 0.09,
        delta: 0.10,
        lambda: 1.6,
        latency: BASS_LATENCY,
    };
    pub const SNARE: Self = Self {
        min_gap: 0.09,
        delta: 0.10,
        lambda: 1.6,
        latency: LATENCY,
    };
    pub const HAT: Self = Self {
        min_gap: 0.05,
        delta: 0.08,
        lambda: 1.4,
        latency: LATENCY,
    };
}

/// Offline peak picking. Non-causal windows are fine: the whole song is available.
///
/// The function is first divided by its local peak level (moving maximum over ±2 s,
/// floored at 5% of the song's level) — adaptive whitening in the sense of Stowell &
/// Plumbley (2007) — so a quiet intro is judged against itself, not against the drop.
/// A frame is then an onset when it is the maximum within ±2 frames, clears the short
/// local mean by `delta`, and clears `lambda` times the ~1 s local median plus `delta`.
pub fn pick(odf: &[f32], fps: f32, p: PickParams) -> Vec<Peak> {
    let n = odf.len();
    let global = scale(odf);
    if n < 3 || quantile(odf, 0.99) < ABS_FLOOR {
        return Vec::new();
    }
    let reach = (2.0 * fps).round() as usize;
    let local_level = moving_max(odf, reach);
    let x: Vec<f32> = odf
        .iter()
        .zip(&local_level)
        .map(|(v, l)| v / l.max(0.05 * global).max(ABS_FLOOR))
        .collect();
    let local = moving_mean(&x, (0.08 * fps).round() as usize);
    let half = (0.5 * fps).round() as usize;
    let mut scratch = Vec::with_capacity(2 * half + 1);
    let gap = (p.min_gap * fps).round() as usize;
    let mut out: Vec<Peak> = Vec::new();
    for i in 1..n - 1 {
        let lo = i.saturating_sub(2);
        let hi = (i + 3).min(n);
        if x[lo..hi].iter().any(|&v| v > x[i]) || x[i] <= x[i - 1] {
            continue;
        }
        if x[i] < local[i] + p.delta {
            continue;
        }
        scratch.clear();
        scratch.extend_from_slice(&x[i.saturating_sub(half)..(i + half + 1).min(n)]);
        let k = scratch.len() / 2;
        let (_, median, _) = scratch.select_nth_unstable_by(k, f32::total_cmp);
        if x[i] < *median * p.lambda + p.delta {
            continue;
        }
        let peak = Peak {
            frame: i,
            time: ((i as f32 + parabolic(&x, i)) / fps + p.latency).max(0.0),
            strength: odf[i] / global,
            local: x[i],
        };
        match out.last_mut() {
            Some(last) if i - last.frame < gap => {
                if peak.local > last.local {
                    *last = peak;
                }
            }
            _ => out.push(peak),
        }
    }
    out
}

/// Centred moving maximum with a half-width of `half` samples (monotonic deque, O(n)).
fn moving_max(v: &[f32], half: usize) -> Vec<f32> {
    let n = v.len();
    let mut out = vec![0.0f32; n];
    let mut dq: std::collections::VecDeque<usize> = std::collections::VecDeque::new();
    let mut next = 0;
    for (i, slot) in out.iter_mut().enumerate() {
        while next < n && next <= i + half {
            while dq.back().is_some_and(|&b| v[b] <= v[next]) {
                dq.pop_back();
            }
            dq.push_back(next);
            next += 1;
        }
        while dq.front().is_some_and(|&f| f + half < i) {
            dq.pop_front();
        }
        *slot = v[*dq.front().unwrap()];
    }
    out
}

/// Drops hits weaker than `floor` of their surroundings (they would only flicker), then
/// rescales strengths so a typical strong hit (90th percentile) is 1.0. Quiet sections
/// keep proportionally smaller, but never invisible, pulses.
pub fn normalize_strengths(peaks: &mut Vec<Peak>, floor: f32) {
    peaks.retain(|p| p.local >= floor);
    let s: Vec<f32> = peaks.iter().map(|p| p.strength).collect();
    let r = quantile(&s, 0.9).max(1e-6);
    for p in peaks.iter_mut() {
        p.strength = (p.strength / r).clamp(0.15, 1.0);
    }
}

/// Renders hits as an exponentially decaying envelope sampled on the frame grid.
/// The attack lands on the frame nearest the sub-frame onset time, so linear frame
/// interpolation in the renderer peaks within half a frame (±11.6 ms) of the attack.
/// `tau` stays 0.15 s: the renderer derives ring age from this exact decay.
pub fn envelope(peaks: &[Peak], frames: usize, fps: f32, tau: f32) -> Vec<f32> {
    let mut out = vec![0.0f32; frames];
    for p in peaks {
        let f0 = ((p.time * fps).round() as usize).min(frames.saturating_sub(1));
        for (f, slot) in out.iter_mut().enumerate().skip(f0) {
            let age = (f as f32 / fps - p.time).max(0.0);
            let v = p.strength * (-age / tau).exp();
            if v < 1e-3 {
                break;
            }
            *slot = slot.max(v);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f32::consts::PI;
    const RATE: f32 = 11025.0;
    const HOP: usize = 256;

    fn detect(pcm: &[f32]) -> Detection {
        let frames = pcm.len().div_ceil(HOP);
        let mut s = ShortSpectrum::new();
        let mut d = Detection::with_capacity(frames);
        for f in 0..frames {
            d.push(s.flux(pcm, f * HOP));
        }
        d.finish();
        d
    }

    fn kick_at(pcm: &mut [f32], t0: f32) {
        let start = (t0 * RATE) as usize;
        for i in 0..(0.3 * RATE) as usize {
            let t = i as f32 / RATE;
            if let Some(s) = pcm.get_mut(start + i) {
                let phase = 2.0 * PI * (50.0 * t + 100.0 * 0.03 * (1.0 - (-t / 0.03).exp()));
                *s += 0.8 * phase.sin() * (-t / 0.12).exp();
            }
        }
    }

    #[test]
    fn calibrated_click_timing() {
        // Kicks at irregular sub-frame positions; mean and worst timing error after
        // latency correction must be well inside one video frame.
        let fps = RATE / HOP as f32;
        let truth: Vec<f32> = (0..24)
            .map(|n| 0.4 + n as f32 * 0.4613 + (n % 5) as f32 * 0.0071)
            .collect();
        let mut pcm = vec![0.0f32; (12.5 * RATE) as usize];
        for &t in &truth {
            kick_at(&mut pcm, t);
        }
        let d = detect(&pcm);
        let peaks = pick(&d.low, fps, PickParams::KICK);
        assert_eq!(peaks.len(), truth.len(), "{peaks:?}");
        let errs: Vec<f32> = peaks.iter().zip(&truth).map(|(p, t)| p.time - t).collect();
        let mean = errs.iter().sum::<f32>() / errs.len() as f32;
        let worst = errs.iter().fold(0.0f32, |m, e| m.max(e.abs()));
        assert!(mean.abs() < 0.005, "mean error {mean}");
        assert!(worst < 0.0125, "worst error {worst}");
    }

    #[test]
    fn quiet_intro_survives_adaptive_threshold() {
        // A quiet intro (20 dB down) then the drop. Whitening judges each hit against the
        // surrounding ±2 s, so intro kicks further than that from the drop must all be
        // found, as must every loud kick.
        let fps = RATE / HOP as f32;
        let mut pcm = vec![0.0f32; (12.0 * RATE) as usize];
        let quiet: Vec<f32> = (0..12).map(|n| 0.3 + n as f32 * 0.5).collect();
        let loud: Vec<f32> = (0..10).map(|n| 6.3 + n as f32 * 0.5).collect();
        let mut tmp = vec![0.0f32; (0.3 * RATE) as usize + 1];
        kick_at(&mut tmp, 0.0);
        for &t in &quiet {
            let start = (t * RATE) as usize;
            for (i, v) in tmp.iter().enumerate() {
                pcm[start + i] += 0.1 * v;
            }
        }
        for &t in &loud {
            kick_at(&mut pcm, t);
        }
        let d = detect(&pcm);
        let found = pick(&d.low, fps, PickParams::KICK);
        let hit = |t: f32| found.iter().any(|p| (p.time - t).abs() < 0.03);
        for &t in quiet.iter().filter(|&&t| t < 6.3 - 2.0) {
            assert!(hit(t), "quiet kick at {t} missed: {found:?}");
        }
        for &t in &loud {
            assert!(hit(t), "loud kick at {t} missed");
        }
        assert!(found.len() <= quiet.len() + loud.len());
    }

    #[test]
    fn steady_tone_and_silence_give_no_onsets() {
        let fps = RATE / HOP as f32;
        let tone: Vec<f32> = (0..(4.0 * RATE) as usize)
            .map(|i| {
                let t = i as f32 / RATE;
                0.5 * (2.0 * PI * 110.0 * t).sin() * (t / 0.5).min(1.0)
            })
            .collect();
        let d = detect(&tone);
        // At most the fade-in start may register.
        let tone_onsets = pick(&d.pulse, fps, PickParams::KICK);
        assert!(tone_onsets.len() <= 1, "{tone_onsets:?}");
        let silent = detect(&vec![0.0; 44100]);
        assert!(pick(&silent.pulse, fps, PickParams::KICK).is_empty());
    }

    #[test]
    fn moving_max_matches_naive() {
        let v: Vec<f32> = (0..50).map(|i| ((i * 37) % 11) as f32).collect();
        let fast = moving_max(&v, 3);
        for i in 0..v.len() {
            let naive = v[i.saturating_sub(3)..(i + 4).min(v.len())]
                .iter()
                .cloned()
                .fold(f32::MIN, f32::max);
            assert_eq!(fast[i], naive);
        }
    }

    #[test]
    fn envelope_peaks_on_nearest_frame_and_decays() {
        let fps = 43.066;
        let p = [Peak {
            frame: 10,
            time: 10.4 / fps,
            strength: 1.0,
            local: 1.0,
        }];
        let e = envelope(&p, 40, fps, 0.15);
        assert_eq!(e[9], 0.0);
        assert!((e[10] - 1.0).abs() < 1e-6);
        assert!(e[11] < 1.0 && e[11] > 0.8);
        assert!(e.iter().all(|v| v.is_finite()));
    }
}

/// Merges two onset lists; hits closer than `window` seconds become one (the stronger).
pub fn merge(a: &[Peak], b: &[Peak], window: f32) -> Vec<Peak> {
    let mut all: Vec<Peak> = a.iter().chain(b).copied().collect();
    all.sort_by(|x, y| x.time.total_cmp(&y.time));
    let mut out: Vec<Peak> = Vec::with_capacity(all.len());
    for p in all {
        match out.last_mut() {
            Some(last) if p.time - last.time < window => {
                if p.strength > last.strength {
                    *last = p;
                }
            }
            _ => out.push(p),
        }
    }
    out
}
