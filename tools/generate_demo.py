#!/usr/bin/env python3
"""Generate SoundWaive's demo from oscillators/noise, with no recordings or samples.

Requires Python 3, FFmpeg and the pinned Rust toolchain. Run from any directory.
Gradle calls this script before packaging; generated media are ignored by Git.
"""
from array import array
from pathlib import Path
import hashlib
import json
import math
import random
import subprocess
import sys
import tempfile
import wave

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
RATE, SECONDS, BPM, SEED = 22050, 32, 120, 171203
FRAMES = RATE * SECONDS
TAU = 2 * math.pi
rng = random.Random(SEED)
left = array('f', [0.0]) * FRAMES
right = array('f', [0.0]) * FRAMES


def add(start, duration, voice, gain=1.0, pan=0.0):
    first = round(start * RATE)
    count = min(round(duration * RATE), FRAMES - first)
    lg, rg = math.sqrt((1 - pan) / 2), math.sqrt((1 + pan) / 2)
    for n in range(max(count, 0)):
        value = gain * voice(n / RATE)
        left[first + n] += value * lg
        right[first + n] += value * rg


def kick(t):
    phase = TAU * (48 * t + 110 * .032 * (1 - math.exp(-t / .032)))
    return math.sin(phase) * math.exp(-t / .14) + rng.uniform(-.12, .12) * math.exp(-t / .006)


def snare(t):
    return (.67 * rng.uniform(-1, 1) + .33 * math.sin(TAU * 183 * t)) * math.exp(-t / .062)


def hat(t):
    return rng.uniform(-1, 1) * math.exp(-t / .018)


# Sixteen bars: drums, bass and spacious oscillator plucks, then a short breakdown.
for bar in range(16):
    base = bar * 2.0
    root = [40, 43, 38, 45][(bar // 4) % 4]
    sparse = bar in (7, 15)
    for beat in (0, 1.5, 2, 3.25) if not sparse else (0, 2):
        add(base + beat * .5, .4, kick, .82)
    for beat in (1, 3):
        add(base + beat * .5, .25, snare, .34)
    for step in range(8 if not sparse else 4):
        add(base + step * .25, .08, hat, .11 if step % 2 else .075, .35 if step % 2 else -.35)
    for offset, interval in [(0.0, 0), (.75, 7), (1.0, 0), (1.625, 3)]:
        hz = 440 * 2 ** ((root + interval - 69) / 12)
        def bass(t, hz=hz):
            envelope = min(1.0, t / .008) * math.exp(-t / .2)
            return (math.sin(TAU * hz * t) + .19 * math.sin(TAU * hz * 2 * t)) * envelope
        add(base + offset, .36, bass, .46)
    for step, interval in enumerate((12, 19, 15, 22)):
        hz = 440 * 2 ** ((root + interval - 69) / 12)
        def pluck(t, hz=hz):
            return math.sin(TAU * hz * t + .65 * math.sin(TAU * hz * 2 * t) * math.exp(-t * 10)) * min(t * 300, 1) * math.exp(-t * 6)
        for echo in range(3):
            add(base + .125 + step * .5 + echo * .1875, .65, pluck, .13 * .38 ** echo, (-.6 if step % 2 else .6) * (-1 if echo % 2 else 1))

peak = max(max(abs(x) for x in left), max(abs(x) for x in right), .001)
interleaved = array('h')
for i, (l, r) in enumerate(zip(left, right)):
    t = i / RATE
    fade = min(1.0, t / .008, max(0.0, (SECONDS - t) / .7))
    for sample in (l, r):
        interleaved.append(round(30000 * sample / peak * fade))
if sys.byteorder != 'little':
    interleaved.byteswap()

ASSETS.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='soundwaive-generated-demo-') as temp:
    temp = Path(temp)
    wav, mono = temp / 'generated.wav', temp / 'analysis.pcm'
    with wave.open(str(wav), 'wb') as stream:
        stream.setnchannels(2)
        stream.setsampwidth(2)
        stream.setframerate(RATE)
        stream.writeframes(interleaved.tobytes())
    audio = ASSETS / 'generated-demo.m4a'
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(wav), '-map_metadata', '-1', '-ar', '44100', '-c:a', 'aac', '-b:a', '160k', str(audio)], check=True)
    # Analyze the decoded bundled audio so the envelope matches actual playback.
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(audio), '-t', str(SECONDS), '-ar', '11025', '-ac', '1', '-f', 's16le', str(mono)], check=True)
    subprocess.run(['cargo', 'run', '--locked', '--release', '--manifest-path', str(ROOT / 'engine/Cargo.toml'), '--', str(mono), str(ASSETS / 'generated-demo.analysis')], cwd=ROOT, check=True)

(ROOT / 'generated-demo.sha256').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(ROOT).as_posix()}\n' for p in sorted(ASSETS.glob('generated-demo.*')) if p.is_file()))
(ROOT / 'docs/generated-demo.json').write_text(json.dumps({'origin': 'Synthesized exclusively from mathematical oscillators and seeded noise by tools/generate_demo.py; no user recording, sample pack or external audio is used.', 'duration_seconds': SECONDS, 'tempo_bpm': BPM, 'seed': SEED, 'playback_sample_rate': 44100, 'channels': 2}, indent=2) + '\n')
print('Generated 32-second demo and analysis; no unreleased recording was read.')
