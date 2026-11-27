//! Audio front end, numerically matching upstream `model.STFT` + `model.Spectrogram`
//! (torch.stft, n_fft=512, hop=256, periodic Hann window, center=False, magnitude, mono).
use realfft::RealFftPlanner;

pub const SAMPLE_RATE: u32 = 16_000;
pub const N_FFT: usize = 512;
pub const HOP: usize = 256;
pub const N_BINS: usize = N_FFT / 2 + 1;

/// Magnitude spectrogram, row-major (frames, 257).
pub fn magnitude_spectrogram(audio: &[f32]) -> (Vec<f32>, usize) {
    if audio.len() < N_FFT {
        return (Vec::new(), 0);
    }
    let frames = 1 + (audio.len() - N_FFT) / HOP;
    // torch.hann_window(512) is periodic: 0.5 - 0.5 cos(2πn/N)
    let window: Vec<f32> = (0..N_FFT)
        .map(|n| (0.5 - 0.5 * (2.0 * std::f64::consts::PI * n as f64 / N_FFT as f64).cos()) as f32)
        .collect();
    let mut planner = RealFftPlanner::<f32>::new();
    let fft = planner.plan_fft_forward(N_FFT);
    let mut inbuf = fft.make_input_vec();
    let mut outbuf = fft.make_output_vec();
    let mut scratch = fft.make_scratch_vec();
    let mut spec = vec![0f32; frames * N_BINS];
    for f in 0..frames {
        let s = &audio[f * HOP..f * HOP + N_FFT];
        for i in 0..N_FFT {
            inbuf[i] = s[i] * window[i];
        }
        fft.process_with_scratch(&mut inbuf, &mut outbuf, &mut scratch)
            .expect("fft");
        let row = &mut spec[f * N_BINS..(f + 1) * N_BINS];
        for (k, c) in outbuf.iter().enumerate() {
            row[k] = (c.re * c.re + c.im * c.im).sqrt();
        }
    }
    (spec, frames)
}

/// Windowed-sinc (Kaiser) resampler to 16 kHz. librosa (what upstream uses) defaults to
/// soxr_hq, so resampled input is close to, but not bit-identical with, the Python path.
pub fn resample_to_16k(x: &[f32], sr: u32) -> Vec<f32> {
    if sr == SAMPLE_RATE {
        return x.to_vec();
    }
    let g = gcd(sr as u64, SAMPLE_RATE as u64);
    let up = SAMPLE_RATE as u64 / g; // L
    let down = sr as u64 / g; // M
    let ratio = SAMPLE_RATE as f64 / sr as f64;
    let cutoff = ratio.min(1.0) * 0.96; // relative to input Nyquist
    let half_zc = 32.0; // zero crossings per side
    let half_len = (half_zc / cutoff).ceil() as i64; // taps per side (input samples)
    let beta = 9.0f64;
    let i0b = bessel_i0(beta);
    let kernel = |d: f64| -> f64 {
        // d in input samples
        let w = d / (half_len as f64);
        if w.abs() >= 1.0 {
            return 0.0;
        }
        let win = bessel_i0(beta * (1.0 - w * w).sqrt()) / i0b;
        let xx = cutoff * d;
        let sinc = if xx.abs() < 1e-12 {
            1.0
        } else {
            (std::f64::consts::PI * xx).sin() / (std::f64::consts::PI * xx)
        };
        cutoff * sinc * win
    };
    let out_len = ((x.len() as u64 * up + down - 1) / down) as usize;
    let taps = (2 * half_len + 1) as usize;
    // polyphase table: phase p = (k*M mod L)/L fractional offset
    let table: Option<Vec<f32>> = if up <= 2048 {
        let mut t = vec![0f32; up as usize * taps];
        for p in 0..up as usize {
            let frac = p as f64 / up as f64;
            for j in 0..taps {
                let i = j as i64 - half_len;
                t[p * taps + j] = kernel(i as f64 - frac) as f32;
            }
        }
        Some(t)
    } else {
        None
    };
    let mut out = vec![0f32; out_len];
    for (k, o) in out.iter_mut().enumerate() {
        let num = k as u64 * down;
        let base = (num / up) as i64;
        let p = (num % up) as usize;
        let mut acc = 0f64;
        for j in 0..taps {
            let i = base + j as i64 - half_len;
            if i < 0 || i as usize >= x.len() {
                continue;
            }
            let c = match &table {
                Some(t) => t[p * taps + j] as f64,
                None => kernel((j as i64 - half_len) as f64 - p as f64 / up as f64),
            };
            acc += c * x[i as usize] as f64;
        }
        *o = acc as f32;
    }
    out
}

fn gcd(a: u64, b: u64) -> u64 {
    if b == 0 {
        a
    } else {
        gcd(b, a % b)
    }
}

fn bessel_i0(x: f64) -> f64 {
    let mut sum = 1.0;
    let mut term = 1.0;
    let q = x * x / 4.0;
    for k in 1..200 {
        term *= q / ((k * k) as f64);
        sum += term;
        if term < 1e-17 * sum {
            break;
        }
    }
    sum
}
