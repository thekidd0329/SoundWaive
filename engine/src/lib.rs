//! Deterministic, offline music analysis. No network and no learned model.
use std::{
    f32::consts::PI,
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
#[derive(Clone, Copy, Default)]
struct Complex {
    r: f32,
    i: f32,
}
fn fft(a: &mut [Complex]) {
    let n = a.len();
    let mut j = 0;
    for i in 1..n {
        let mut b = n >> 1;
        while j & b != 0 {
            j ^= b;
            b >>= 1;
        }
        j ^= b;
        if i < j {
            a.swap(i, j);
        }
    }
    let mut len = 2;
    while len <= n {
        let ang = -2.0 * PI / len as f32;
        let wr = ang.cos();
        let wi = ang.sin();
        for start in (0..n).step_by(len) {
            let (mut xr, mut xi) = (1.0, 0.0);
            for k in 0..len / 2 {
                let u = a[start + k];
                let p = a[start + k + len / 2];
                let v = Complex {
                    r: p.r * xr - p.i * xi,
                    i: p.r * xi + p.i * xr,
                };
                a[start + k] = Complex {
                    r: u.r + v.r,
                    i: u.i + v.i,
                };
                a[start + k + len / 2] = Complex {
                    r: u.r - v.r,
                    i: u.i - v.i,
                };
                let nr = xr * wr - xi * wi;
                xi = xr * wi + xi * wr;
                xr = nr;
            }
        }
        len <<= 1;
    }
}
fn quantile(v: &[f32], q: f32) -> f32 {
    let mut a = v.to_vec();
    a.sort_by(f32::total_cmp);
    a[((a.len() - 1) as f32 * q) as usize]
}
fn normalize(v: &mut [f32]) {
    let lo = quantile(v, 0.05);
    let hi = quantile(v, 0.97).max(lo + 1e-6);
    for x in v {
        *x = ((*x - lo) / (hi - lo)).clamp(0.0, 1.0);
    }
}
fn smooth(v: &[f32], attack: f32, release: f32) -> Vec<f32> {
    let mut z = 0.0;
    v.iter()
        .map(|&x| {
            z += (x - z) * if x > z { attack } else { release };
            z
        })
        .collect()
}
fn attacks(v: &[f32], fps: f32) -> Vec<f32> {
    let mut out = vec![0.0; v.len()];
    let mut last = 0usize;
    for i in 2..v.len().saturating_sub(2) {
        let a = i.saturating_sub((fps * 0.35) as usize);
        let mean = v[a..i].iter().sum::<f32>() / (i - a).max(1) as f32;
        if v[i] > mean * 1.45 + 0.065
            && v[i] >= v[i - 1]
            && v[i] > v[i + 1]
            && i - last > (fps * 0.09) as usize
        {
            out[i] = v[i];
            last = i;
        }
    }
    let mut z: f32 = 0.0;
    for x in &mut out {
        z = (*x).max(z * (-1.0 / (fps * 0.15)).exp());
        *x = z;
    }
    out
}
fn tempo(flux: &[f32], fps: f32) -> f32 {
    let mut best = (0.0, 120.0);
    for bpm in 70..181 {
        let lag = (fps * 60.0 / bpm as f32).round() as usize;
        if lag >= flux.len() {
            continue;
        }
        let mut score = 0.0;
        for i in lag..flux.len() {
            score += flux[i] * flux[i - lag];
        }
        score /= (flux.len() - lag) as f32;
        score *= 1.0 - ((bpm as f32 - 120.0).abs() / 180.0);
        if score > best.0 {
            best = (score, bpm as f32);
        }
    }
    best.1
}
#[derive(Debug, Clone)]
pub struct Report {
    pub bpm: f32,
    pub frames: usize,
    pub notes: usize,
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
    let frames = (pcm.len() + HOP - 1) / HOP;
    let fps = RATE / HOP as f32;
    let mut data = vec![0.0f32; frames * STRIDE];
    let mut low = vec![0.0; frames];
    let mut mid = low.clone();
    let mut high = low.clone();
    let mut rms = low.clone();
    let mut flux = low.clone();
    let mut lf = low.clone();
    let mut hf = low.clone();
    let mut pitches = vec![0; frames];
    let mut previous = vec![0.0; N / 2];
    let mut a = vec![Complex::default(); N];
    let mut magnitude = vec![0.0; N / 2];
    let window: Vec<f32> = (0..N)
        .map(|i| 0.5 - 0.5 * (2.0 * PI * i as f32 / (N - 1) as f32).cos())
        .collect();
    for f in 0..frames {
        let center = f * HOP;
        let mut energy = 0.0;
        for j in 0..N {
            let at = center as isize + j as isize - (N / 2) as isize;
            let v = if at >= 0 {
                *pcm.get(at as usize).unwrap_or(&0.0)
            } else {
                0.0
            };
            a[j] = Complex {
                r: v * window[j],
                i: 0.0,
            };
            energy += v * v;
        }
        rms[f] = (energy / N as f32).sqrt();
        fft(&mut a);
        for k in 1..N / 2 {
            let m = (a[k].r * a[k].r + a[k].i * a[k].i).sqrt() / N as f32;
            magnitude[k] = m;
            let hz = k as f32 * RATE / N as f32;
            let rise = (m - previous[k]).max(0.0);
            if hz < 220.0 {
                low[f] += m * m;
                lf[f] += rise;
            } else if hz < 2200.0 {
                mid[f] += m * m;
            } else {
                high[f] += m * m;
                hf[f] += rise;
            }
            flux[f] += rise;
            previous[k] = m;
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
    let bpm = tempo(&flux, fps);
    for v in [
        &mut low, &mut mid, &mut high, &mut rms, &mut flux, &mut lf, &mut hf,
    ] {
        normalize(v);
    }
    let low = smooth(&low, 0.65, 0.28);
    let mid = smooth(&mid, 0.65, 0.28);
    let high = smooth(&high, 0.8, 0.4);
    let slow = smooth(&rms, 0.025, 0.025);
    let kick = attacks(&lf, fps);
    let snare = attacks(&flux, fps);
    let hat = attacks(&hf, fps);
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
    let onsets: Vec<[f32; 2]> = (1..frames)
        .filter(|&f| snare[f] > 0.26 && snare[f] > snare[f - 1] + 0.03)
        .map(|f| [f as f32 / fps, snare[f]])
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
    out.flush()?;
    Ok(Report {
        bpm,
        frames,
        notes: notes.len(),
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
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fft_finds_sine() {
        let mut a: Vec<Complex> = (0..N)
            .map(|i| Complex {
                r: (2.0 * PI * 32.0 * i as f32 / N as f32).sin(),
                i: 0.0,
            })
            .collect();
        fft(&mut a);
        let k = (1..N / 2)
            .max_by(|&i, &j| {
                (a[i].r * a[i].r + a[i].i * a[i].i).total_cmp(&(a[j].r * a[j].r + a[j].i * a[j].i))
            })
            .unwrap();
        assert_eq!(k, 32);
    }
    #[test]
    fn silence_remains_finite() {
        let mut v = vec![0.0; 100];
        normalize(&mut v);
        assert!(v.iter().all(|x| x.is_finite() && *x == 0.0));
    }
    #[test]
    fn beat_train_detects_120() {
        let fps = 43.0664;
        let mut v = vec![0.0; 4300];
        for n in 0..200 {
            let i = (n as f32 * fps * 0.5) as usize;
            if i < v.len() {
                v[i] = 1.0;
            }
        }
        assert!((tempo(&v, fps) - 120.0).abs() < 4.0);
    }
    #[test]
    fn full_analysis_roundtrip() {
        let root = std::env::temp_dir();
        let input = root.join("crownpulse_test.pcm");
        let output = root.join("crownpulse_test.bin");
        let mut pcm = Vec::new();
        for i in 0..44100 {
            let t = i as f32 / RATE;
            let envelope = if t % 0.5 < 0.12 { 0.8 } else { 0.12 };
            let v = ((2.0 * PI * 82.4 * t).sin() * envelope * 32767.0) as i16;
            pcm.extend_from_slice(&v.to_le_bytes());
        }
        fs::write(&input, pcm).unwrap();
        let r = analyze_pcm(&input, &output).unwrap();
        assert!(r.frames > 100 && r.notes > 0);
        assert_eq!(&fs::read(&output).unwrap()[..8], b"CPULSE01");
        let _ = fs::remove_file(input);
        let _ = fs::remove_file(output);
    }
}
