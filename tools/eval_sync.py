#!/usr/bin/env python3
"""Measure how accurately the analysis engine finds kicks, onsets, tempo and the beat grid.

Synthesizes drum/bass/pad test songs from oscillators and seeded noise (no recordings),
feeds them through the same 11025 Hz mono decimation the Android app uses
(AudioEngine.kt), runs the engine CLI and compares its CPULSE01 output with the known
ground truth. Every number here is reproducible from the seed.

  python3 tools/eval_sync.py                       # build engine from this checkout
  python3 tools/eval_sync.py --engine path/to/bin  # evaluate another engine build
  python3 tools/eval_sync.py --json out.json --plot-dir previews/

Requires Python 3 + NumPy and Cargo (unless --engine is given); Matplotlib only for plots.
"""
from __future__ import annotations

import argparse
import json
import struct
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SR = 44100
TOL_ONSET = 0.050  # MIREX-style onset tolerance, seconds
TOL_BEAT = 0.070   # beat tolerance (continuity-free F-measure)


@dataclass
class Song:
    name: str
    bpm: float
    audio: np.ndarray
    kicks: list = field(default_factory=list)
    snares: list = field(default_factory=list)
    beats: list = field(default_factory=list)
    downbeats: list = field(default_factory=list)


# ----------------------------------------------------------------------------- synthesis
class Synth:
    def __init__(self, seconds: float, seed: int):
        self.n = int(seconds * SR)
        self.buf = np.zeros(self.n)
        self.rng = np.random.default_rng(seed)

    def add(self, start: float, sig: np.ndarray, gain: float = 1.0):
        i = int(round(start * SR))
        if i >= self.n:
            return
        sig = sig[: self.n - i]
        self.buf[i : i + len(sig)] += gain * sig

    def t(self, dur):
        return np.arange(int(dur * SR)) / SR

    def kick(self, f0=48.0, sweep=110.0, decay=0.14):
        t = self.t(0.5)
        phase = 2 * np.pi * (f0 * t + sweep * 0.032 * (1 - np.exp(-t / 0.032)))
        click = self.rng.uniform(-0.15, 0.15, len(t)) * np.exp(-t / 0.004)
        return np.sin(phase) * np.exp(-t / decay) + click

    def snare(self, decay=0.07):
        t = self.t(0.35)
        noise = self.rng.uniform(-1, 1, len(t))
        return (0.7 * noise + 0.3 * np.sin(2 * np.pi * 185 * t)) * np.exp(-t / decay)

    def hat(self, decay=0.02):
        t = self.t(0.12)
        noise = np.diff(self.rng.uniform(-1, 1, len(t) + 1))  # crude high-pass
        return noise * np.exp(-t / decay)

    def bass(self, midi, dur, glide=0.0):
        t = self.t(dur)
        hz = 440 * 2 ** ((midi - 69) / 12) * (1 + glide * np.exp(-t / 0.05))
        ph = 2 * np.pi * np.cumsum(hz) / SR
        env = np.minimum(1, t / 0.006) * np.exp(-t / max(dur * 0.6, 0.05))
        return (np.sin(ph) + 0.25 * np.sin(2 * ph) + 0.1 * np.sin(3 * ph)) * env

    def pad(self, midis, start, dur, gain):
        t = self.t(dur)
        sig = sum(np.sin(2 * np.pi * 440 * 2 ** ((m - 69) / 12) * t + k) for k, m in enumerate(midis))
        lfo = 0.6 + 0.4 * np.sin(2 * np.pi * 0.25 * t)
        env = np.minimum(1, t / 0.8) * np.minimum(1, (dur - t) / 0.8)
        self.add(start, sig * lfo * env / len(midis), gain)

    def reverb(self, wet=0.25, length=1.2):
        t = self.t(length)
        ir = self.rng.normal(0, 1, len(t)) * np.exp(-t / (length / 5))
        ir /= np.sqrt(np.sum(ir**2))
        m = 1 << int(np.ceil(np.log2(self.n + len(ir))))
        wetsig = np.fft.irfft(np.fft.rfft(self.buf, m) * np.fft.rfft(ir, m), m)[: self.n]
        self.buf = self.buf + wet * wetsig

    def noise(self, level):
        self.buf += self.rng.normal(0, level, self.n)


def pattern_song(name, bpm, seconds, seed, kicks, snares, hats, *, intro=0.0, swing=0.0,
                 kick_gain=0.9, snare_gain=0.4, hat_gain=0.12, bass=None, pad=0.0,
                 reverb=0.0, noise=0.0, b808=False, roll_every=0):
    s = Synth(seconds, seed)
    song = Song(name, bpm, np.zeros(1))
    beat = 60.0 / bpm
    bars = int((seconds - intro) / (4 * beat))
    roots = [40, 43, 38, 45]
    for bar in range(bars):
        t0 = intro + bar * 4 * beat
        root = roots[bar % 4]
        for b in range(4):
            song.beats.append(t0 + b * beat)
        song.downbeats.append(t0)
        for k in kicks:
            at = t0 + k * beat
            song.kicks.append(at)
            s.add(at, s.kick(), kick_gain)
            if b808:
                s.add(at, s.bass(root - 12 + 12, 0.9, glide=0.6), 0.55)
        for k in snares:
            at = t0 + k * beat
            song.snares.append(at)
            s.add(at, s.snare(), snare_gain)
        for k in hats:
            sw = swing * beat / 4 if (k * 4) % 2 == 1 else 0.0
            s.add(t0 + k * beat + sw, s.hat(), hat_gain * (1.0 if (k * 2) % 2 == 0 else 0.7))
        if roll_every and bar % roll_every == roll_every - 1:
            for r in range(8):
                s.add(t0 + 3 * beat + r * beat / 8, s.hat(0.012), hat_gain * 0.8)
        if bass:
            for off, iv, dur in bass:
                s.add(t0 + off * beat, s.bass(root + iv, dur * beat), 0.45)
        if pad:
            s.pad([root + 24, root + 28, root + 31], t0, 4 * beat, pad)
    if reverb:
        s.reverb(reverb)
    if noise:
        s.noise(noise)
    song.audio = s.buf / max(np.max(np.abs(s.buf)), 1e-9) * 0.9
    return song


def songs():
    q8 = [i / 2 for i in range(8)]
    q16 = [i / 4 for i in range(16)]
    return [
        pattern_song("four_floor_120", 120, 30, 1, [0, 1, 2, 3], [1, 3], q8),
        pattern_song("house_128_pad", 128, 30, 2, [0, 1, 2, 3], [1, 3], [0.5, 1.5, 2.5, 3.5],
                     bass=[(0.5, 0, .4), (1.5, 0, .4), (2.5, 7, .4), (3.5, 5, .4)], pad=0.5),
        pattern_song("hiphop_90_swing", 90, 30, 3, [0, 1.75, 2.5], [1, 3], q16, swing=0.35,
                     bass=[(0, 0, 1.5), (2.5, 3, 1.0)], reverb=0.3),
        pattern_song("dnb_174", 174, 30, 4, [0, 2.5], [1, 3], q8, hat_gain=0.09),
        pattern_song("trap_140_808", 140, 30, 5, [0, 0.75, 2.5], [2], q8, b808=True, roll_every=2),
        pattern_song("odd_123_4_intro", 123.4, 60, 6, [0, 1, 2, 3], [1, 3], q8, intro=1.37,
                     bass=[(0, 0, .9), (1, 0, .9), (2, 7, .9), (3, 5, .9)]),
        pattern_song("ballad_76_soft", 76, 40, 7, [0, 2.5], [1, 3], q8, kick_gain=0.5,
                     snare_gain=0.22, pad=1.0, reverb=0.4),
        pattern_song("break_100_noise", 100, 30, 8, [0, 0.75, 2.25, 2.5], [1, 3, 3.75], q16,
                     noise=0.03, bass=[(0, 0, .7), (2.25, -2, 1.0)]),
    ]


def stress_song():
    """One arrangement with everything that breaks naive detectors, in order:
    a quiet intro (pad + kicks 20 dB down), full drums with sustained 808s gliding
    under the kicks at 100 BPM, a 4-bar breakdown with no drums (beats continue in the
    pad chords), then a tempo change to 128 BPM with swung hats."""
    s = Synth(64, 31)
    song = Song("stress_arrangement", 100.0, np.zeros(1))
    t = 0.0
    roots = [40, 43, 38, 45]
    sections = [(100, 4, "quiet"), (100, 8, "full"), (100, 4, "breakdown"), (128, 12, "full")]
    for bpm, bars, kind in sections:
        beat = 60.0 / bpm
        for bar in range(bars):
            root = roots[bar % 4]
            song.downbeats.append(t)
            s.pad([root + 24, root + 28, root + 31], t, 4 * beat, 0.35)
            for b in range(4):
                song.beats.append(t + b * beat)
            if kind != "breakdown":
                kick_gain = 0.09 if kind == "quiet" else 0.9
                for k in ([0, 2] if kind == "quiet" else [0, 1.5, 2, 3]):
                    song.kicks.append(t + k * beat)
                    s.add(t + k * beat, s.kick(), kick_gain)
                    if kind == "full" and k in (0, 2):
                        s.add(t + k * beat, s.bass(root - 12, 1.6 * beat, glide=0.5), 0.5)
                if kind == "full":
                    for k in (1, 3):
                        song.snares.append(t + k * beat)
                        s.add(t + k * beat, s.snare(), 0.4)
                for h in range(8):
                    sw = 0.3 * beat / 2 if (h % 2 and bpm == 128) else 0.0
                    s.add(t + h * beat / 2 + sw, s.hat(), 0.1 if kind == "full" else 0.02)
            t += 4 * beat
    song.audio = s.buf / max(np.max(np.abs(s.buf)), 1e-9) * 0.9
    return song


def holdout_songs():
    """Never used while tuning parameters; reported separately to expose over-fitting."""
    q8 = [i / 2 for i in range(8)]
    return [
        pattern_song("holdout_demo_120", 120, 32, 171203, [0, 1.5, 2, 3.25], [1, 3], q8,
                     snare_gain=0.34, hat_gain=0.11,
                     bass=[(0, 0, .7), (1.5, 7, .7), (2, 0, .7), (3.25, 3, .7)]),
        pattern_song("holdout_dembow_96", 96, 30, 21, [0, 1, 2, 3], [0.75, 1.5, 2.75, 3.5], q8,
                     bass=[(0, 0, 1.0), (2, 5, 1.0)], pad=0.4),
        pattern_song("holdout_rock_112", 112, 30, 22, [0, 2.5], [1, 3], q8, reverb=0.3,
                     noise=0.01, pad=0.6, bass=[(0, 0, 2.0), (2.5, 0, 1.5)]),
    ]


# ----------------------------------------------------------------------------- pipeline
def box_decimate(audio: np.ndarray, rate: int = SR) -> np.ndarray:
    """Mirror MonoDownsampler.kt: average every input frame since the previous output."""
    acc, step, total, count, keep = 0.0, 11025.0 / rate, 0.0, 0, []
    if abs(rate / 11025 - round(rate / 11025)) < 1e-9:
        r = int(round(rate / 11025))
        n = len(audio) // r * r
        out = audio[:n].reshape(-1, r).mean(axis=1)
    else:
        for v in audio:
            total += v
            count += 1
            acc += step
            if acc >= 1:
                keep.append(total / count)
                total, count = 0.0, 0
                acc -= 1
        out = np.array(keep)
    return np.clip(np.round(out * 32767), -32768, 32767).astype("<i2")


def app_decimate(audio: np.ndarray, rate: int = SR) -> np.ndarray:
    """Mirror the original AudioEngine.kt: accumulator nearest-sample pick (aliases)."""
    acc, step, keep = 0.0, 11025.0 / rate, []
    # Vectorized equivalent of the accumulator loop for an exact integer ratio.
    if abs(rate / 11025 - round(rate / 11025)) < 1e-9:
        r = int(round(rate / 11025))
        out = audio[r - 1 :: r]
    else:  # generic path, slow but exact
        for i, v in enumerate(audio):
            acc += step
            if acc >= 1:
                keep.append(v)
                acc -= 1
        out = np.array(keep)
    return np.clip(np.round(out * 32767), -32768, 32767).astype("<i2")


def run_engine(engine: Path, pcm: np.ndarray, tmp: Path, name: str) -> dict:
    src, dst = tmp / f"{name}.pcm", tmp / f"{name}.bin"
    pcm.tofile(src)
    subprocess.run([str(engine), str(src), str(dst)], check=True, capture_output=True)
    return parse_analysis(dst.read_bytes())


def parse_analysis(raw: bytes) -> dict:
    assert raw[:8] == b"CPULSE01", "bad magic"
    duration, fps, bpm, frames, bands, waves = struct.unpack_from("<6f", raw, 8)
    frames, bands, waves = int(frames), int(bands), int(waves)
    stride = 8 + bands + waves
    off = 32
    data = np.frombuffer(raw, "<f4", frames * stride, off).reshape(frames, stride)
    off += frames * stride * 4
    (n,) = struct.unpack_from("<I", raw, off)
    off += 4 + n * 16
    (m,) = struct.unpack_from("<I", raw, off)
    onsets = np.frombuffer(raw, "<f4", m * 2, off + 4).reshape(m, 2) if m else np.zeros((0, 2))
    off += 4 + m * 8
    beats = None
    if raw[off : off + 8] == b"SWBEAT01":
        (k,) = struct.unpack_from("<I", raw, off + 8)
        beats = np.frombuffer(raw, "<f4", k * 3, off + 12).reshape(k, 3)
    return dict(duration=duration, fps=fps, bpm=bpm, data=data, onsets=onsets, beats=beats)


# ----------------------------------------------------------------------------- metrics
def match(est, ref, tol):
    est, ref = sorted(est), sorted(ref)
    used, errs, i = set(), [], 0
    for r in ref:
        best, bj = tol + 1, -1
        for j, e in enumerate(est):
            if j in used:
                continue
            d = e - r
            if abs(d) <= tol and abs(d) < abs(best):
                best, bj = d, j
        if bj >= 0:
            used.add(bj)
            errs.append(best)
    tp = len(errs)
    p = tp / len(est) if est else 0.0
    rr = tp / len(ref) if ref else 0.0
    f = 2 * p * rr / (p + rr) if p + rr else 0.0
    e = np.array(errs) * 1000 if errs else np.zeros(1)
    return dict(p=p, r=rr, f=f, mean_ms=float(e.mean()), mae_ms=float(np.abs(e).mean()))


def envelope_hits(env, fps, rise=0.08):
    hits = []
    for f in range(1, len(env) - 1):
        if env[f] - env[f - 1] > rise and env[f] >= env[f + 1]:
            hits.append(f / fps)
    return hits


def implied_grid(bpm, duration, every=1):
    step = 60.0 / bpm * every
    return list(np.arange(0, duration, step))


def evaluate(song: Song, res: dict) -> dict:
    fps, data = res["fps"], res["data"]
    salient = sorted(set(round(x, 4) for x in song.kicks + song.snares))
    out = dict(true_bpm=song.bpm, bpm=res["bpm"],
               bpm_err_pct=abs(res["bpm"] - song.bpm) / song.bpm * 100)
    out["kick_env"] = match(envelope_hits(data[:, 5], fps), song.kicks, TOL_ONSET)
    out["snare_env"] = match(envelope_hits(data[:, 6], fps), song.snares, TOL_ONSET)
    out["onsets"] = match(list(res["onsets"][:, 0]), salient, TOL_ONSET)
    if res["beats"] is not None and len(res["beats"]):
        b = res["beats"]
        out["grid_source"] = "beat tracker"
        out["beats"] = match(list(b[:, 0]), song.beats, TOL_BEAT)
        out["downbeats"] = match(list(b[b[:, 2] > 0.5, 0]), song.downbeats, TOL_BEAT)
    else:  # what the renderer assumes today: bars from t=0 at the reported BPM
        out["grid_source"] = "bpm from t=0"
        out["beats"] = match(implied_grid(res["bpm"], res["duration"]), song.beats, TOL_BEAT)
        out["downbeats"] = match(implied_grid(res["bpm"], res["duration"], 4), song.downbeats, TOL_BEAT)
    return out


SUMMARY_KEYS = [("kick_env", "f"), ("kick_env", "mean_ms"), ("kick_env", "mae_ms"),
                ("snare_env", "f"), ("snare_env", "mean_ms"), ("snare_env", "mae_ms"), ("onsets", "f"), ("onsets", "mean_ms"), ("onsets", "mae_ms"),
                ("beats", "f"), ("downbeats", "f")]


def octave_ok(r):
    return any(abs(r["bpm"] - r["true_bpm"] * m) / (r["true_bpm"] * m) < 0.01 for m in (0.5, 1, 2))


def summarize(results: dict) -> dict:
    s = {"bpm_err_pct": float(np.mean([r["bpm_err_pct"] for r in results.values()])),
         "bpm_within_1pct": int(sum(r["bpm_err_pct"] < 1 for r in results.values())),
         "bpm_octave_ok": int(sum(octave_ok(r) for r in results.values())),
         "songs": len(results)}
    for a, b in SUMMARY_KEYS:
        s[f"{a}.{b}"] = float(np.mean([r[a][b] for r in results.values()]))
    return s


def plot(song: Song, res: dict, path: Path, label: str):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fps, d = res["fps"], res["data"]
    t = np.arange(len(d)) / fps
    lo, hi = 2.0, 8.0
    sel = (t >= lo) & (t <= hi)
    fig, ax = plt.subplots(3, 1, figsize=(12, 6.5), sharex=True)
    for a, ch, ref, title in [(ax[0], 5, song.kicks, "kick envelope (ch 5)"),
                              (ax[1], 6, song.snares, "snare envelope (ch 6)")]:
        a.plot(t[sel], d[sel, ch], color="#F04A37")
        a.plot(t[sel], d[sel, 0], color="#B36BEE", alpha=.5, lw=1, label="bass (ch 0)") if ch == 5 else None
        for x in ref:
            if lo <= x <= hi:
                a.axvline(x, color="k", lw=.8, ls="--")
        a.set_title(title, loc="left", fontsize=9)
    for x in song.beats:
        if lo <= x <= hi:
            ax[2].axvline(x, color="k", lw=.8, ls="--")
    if res["beats"] is not None:
        for bt, st, db in res["beats"]:
            if lo <= bt <= hi:
                ax[2].plot([bt, bt], [0, 1.0 if db else .6], color="#DDF75D" if not db else "#F04A37", lw=3)
    else:
        for bt in implied_grid(res["bpm"], res["duration"]):
            if lo <= bt <= hi:
                ax[2].plot([bt, bt], [0, .6], color="#48E5F0", lw=3)
    ax[2].set_title("beat grid (dashed = truth; red = detected downbeat)", loc="left", fontsize=9)
    ax[2].set_xlabel("seconds")
    fig.suptitle(f"{label}: {song.name} (true {song.bpm} BPM, reported {res['bpm']:.2f})")
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    plt.close(fig)


def build_engine() -> Path:
    subprocess.run(["cargo", "build", "--release", "--locked", "--manifest-path",
                    str(ROOT / "engine/Cargo.toml")], check=True)
    return ROOT / "engine/target/release/crownpulse_engine"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", type=Path)
    ap.add_argument("--label", default="engine")
    ap.add_argument("--json", type=Path)
    ap.add_argument("--plot-dir", type=Path)
    ap.add_argument("--holdout", action="store_true", help="also run the held-out songs")
    ap.add_argument("--decimator", choices=["box", "legacy"], default="box",
                    help="box = averaging MonoDownsampler (current app); legacy = 1.0.1 sample pick")
    args = ap.parse_args()
    engine = args.engine or build_engine()
    results = {}
    with tempfile.TemporaryDirectory(prefix="soundwaive-eval-") as tmp:
        for song in songs() + [stress_song()] + (holdout_songs() if args.holdout else []):
            dec = box_decimate if args.decimator == "box" else app_decimate
            res = run_engine(engine, dec(song.audio), Path(tmp), song.name)
            results[song.name] = evaluate(song, res)
            if args.plot_dir:
                args.plot_dir.mkdir(parents=True, exist_ok=True)
                plot(song, res, args.plot_dir / f"{args.label}-{song.name}.png", args.label)
    hdr = f"{'song':18} {'bpm':>7} {'err%':>5} {'kickF':>5} {'kick±ms':>8} {'snrF':>5} {'onsF':>5} {'ons±ms':>7} {'beatF':>5} {'barF':>5}"
    print(hdr)
    for n, r in results.items():
        print(f"{n:18} {r['bpm']:7.2f} {r['bpm_err_pct']:5.1f} {r['kick_env']['f']:5.2f} "
              f"{r['kick_env']['mean_ms']:+8.1f} {r['snare_env']['f']:5.2f} {r['onsets']['f']:5.2f} "
              f"{r['onsets']['mean_ms']:+7.1f} {r['beats']['f']:5.2f} {r['downbeats']['f']:5.2f}")
    summary = summarize({k: v for k, v in results.items()
                         if not k.startswith(("holdout", "stress"))})
    print("tuning summary:", json.dumps({k: round(v, 3) for k, v in summary.items()}))
    out = {"label": args.label, "summary": summary, "songs": results,
           "stress": results["stress_arrangement"]}
    if args.holdout:
        out["holdout_summary"] = summarize({k: v for k, v in results.items() if k.startswith("holdout")})
        print("holdout summary:", json.dumps({k: round(v, 3) for k, v in out["holdout_summary"].items()}))
    if args.json:
        args.json.write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    sys.exit(main())
