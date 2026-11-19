//! Small, dependency-free signal-processing helpers shared by the analysis stages.
use std::f32::consts::PI;

#[derive(Clone, Copy, Default)]
pub struct Complex {
    pub r: f32,
    pub i: f32,
}

impl Complex {
    pub fn norm(self) -> f32 {
        (self.r * self.r + self.i * self.i).sqrt()
    }
}

/// In-place radix-2 FFT. `a.len()` must be a power of two.
pub fn fft(a: &mut [Complex]) {
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

/// Periodic-free symmetric Hann window.
pub fn hann(n: usize) -> Vec<f32> {
    (0..n)
        .map(|i| 0.5 - 0.5 * (2.0 * PI * i as f32 / (n - 1) as f32).cos())
        .collect()
}

/// Fills `a` with `window`-weighted samples centred on `center` (zero outside the signal).
pub fn load_frame(pcm: &[f32], center: usize, window: &[f32], a: &mut [Complex]) {
    let half = window.len() / 2;
    for (j, w) in window.iter().enumerate() {
        let at = center as isize + j as isize - half as isize;
        let v = if at >= 0 {
            *pcm.get(at as usize).unwrap_or(&0.0)
        } else {
            0.0
        };
        a[j] = Complex { r: v * w, i: 0.0 };
    }
}

pub fn quantile(v: &[f32], q: f32) -> f32 {
    if v.is_empty() {
        return 0.0;
    }
    let mut a = v.to_vec();
    let k = ((a.len() - 1) as f32 * q.clamp(0.0, 1.0)) as usize;
    let (_, x, _) = a.select_nth_unstable_by(k, f32::total_cmp);
    *x
}

pub fn normalize(v: &mut [f32]) {
    let lo = quantile(v, 0.05);
    let hi = quantile(v, 0.97).max(lo + 1e-6);
    for x in v {
        *x = ((*x - lo) / (hi - lo)).clamp(0.0, 1.0);
    }
}

/// One-pole attack/release follower.
pub fn smooth(v: &[f32], attack: f32, release: f32) -> Vec<f32> {
    let mut z = 0.0;
    v.iter()
        .map(|&x| {
            z += (x - z) * if x > z { attack } else { release };
            z
        })
        .collect()
}

/// Centred moving average with a half-width of `half` samples (edges use the available span).
pub fn moving_mean(v: &[f32], half: usize) -> Vec<f32> {
    let mut prefix = Vec::with_capacity(v.len() + 1);
    prefix.push(0.0f64);
    for &x in v {
        prefix.push(prefix.last().unwrap() + x as f64);
    }
    (0..v.len())
        .map(|i| {
            let a = i.saturating_sub(half);
            let b = (i + half + 1).min(v.len());
            ((prefix[b] - prefix[a]) / (b - a) as f64) as f32
        })
        .collect()
}

/// Sub-sample offset (-0.5..0.5) of a peak at `i` by fitting a parabola through its neighbours.
pub fn parabolic(v: &[f32], i: usize) -> f32 {
    if i == 0 || i + 1 >= v.len() {
        return 0.0;
    }
    let (a, b, c) = (v[i - 1], v[i], v[i + 1]);
    let d = a - 2.0 * b + c;
    if d.abs() < 1e-12 {
        0.0
    } else {
        (0.5 * (a - c) / d).clamp(-0.5, 0.5)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fft_finds_sine() {
        let n = 2048;
        let mut a: Vec<Complex> = (0..n)
            .map(|i| Complex {
                r: (2.0 * PI * 32.0 * i as f32 / n as f32).sin(),
                i: 0.0,
            })
            .collect();
        fft(&mut a);
        let k = (1..n / 2)
            .max_by(|&i, &j| a[i].norm().total_cmp(&a[j].norm()))
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
    fn parabolic_recovers_offset() {
        // Samples of a parabola peaking at 10.3.
        let v: Vec<f32> = (0..20).map(|i| 5.0 - (i as f32 - 10.3).powi(2)).collect();
        assert!((parabolic(&v, 10) - 0.3).abs() < 1e-4);
    }

    #[test]
    fn moving_mean_is_centred() {
        let v = [0.0, 0.0, 3.0, 0.0, 0.0];
        assert_eq!(moving_mean(&v, 1), vec![0.0, 1.0, 1.0, 1.0, 0.0]);
    }
}
