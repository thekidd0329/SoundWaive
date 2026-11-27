# lyrics-aligner (vendored)

Rust port of [schufo/lyrics-aligner](https://github.com/schufo/lyrics-aligner) (MIT, see
`LICENSE`): phoneme and word level alignment of known lyrics to a song, from K.
Schulze-Forster, C. Doire, G. Richard and R. Badeau, "Phoneme Level Lyrics Alignment and
Text-Informed Singing Voice Separation", IEEE/ACM TASLP 29 (2021).

How it works, unchanged from upstream: 16 kHz magnitude STFT (n_fft 512, hop 256), lyrics
turned into ARPAbet phonemes with the CMU Pronouncing Dictionary (`CMUDICT_LICENSE`), the
upstream network up to its attention scores run with [tract](https://github.com/sonos/tract)
(`scores.onnx`), a monotonic DTW over the scores, and an optional voice-activity pass
(`voice.onnx`). Word timings match the Python original (0.0 ms difference on the reference
songs).

Changes for SoundWaive:

- Library only (`rlib`), linked into `crownpulse_engine`; the JNI entry is
  `engine/src/lyrics.rs`. The standalone CLI, C ABI and WAV reader are not included.
- Nothing large is compiled in: the models and the dictionary are app assets
  (`app/src/main/assets/lyrics/`) loaded at runtime (`Aligner::from_files`,
  `g2p::init_cmudict`).
- Refuses alignments larger than `MAX_CELLS` frames x phoneme tokens, so a very long song
  with long lyrics fails with a message instead of exhausting a phone's memory.

The ONNX export, parity scripts and test audio stay with the standalone port; they are not
needed to build the app.
