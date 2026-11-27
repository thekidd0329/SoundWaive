# Third-party notices

Kotlin standard library: Apache License 2.0, JetBrains. Gradle wrapper: Apache License 2.0, Gradle. Android runtime components are supplied by the device.

The 32-bit Intel build includes the LiteRT 1.4.2 CPU interpreter from Google. Its distribution license is included in [LiteRT-1.4.2-LICENSE](LiteRT-1.4.2-LICENSE).

Rust dependency lockfile includes the following packages. Platform-specific or build-only entries (for example the `windows-*` and `r-efi` crates) may not be present in the Android binary. License texts are included in this directory as `<crate>-<version>-<file>`. `jni-sys-macros`, `nom-language`, `tract-extra` and `tract-transformers` publish no license file; they are MIT, or MIT OR Apache-2.0 (see [Apache-2.0.txt](Apache-2.0.txt)). `r-efi` is used under MIT.

- adler2 2.0.1: 0BSD OR MIT OR Apache-2.0
- anyhow 1.0.105: MIT OR Apache-2.0
- anymap3 1.1.0: BlueOak-1.0.0 OR MIT OR Apache-2.0
- autocfg 1.5.1: Apache-2.0 OR MIT
- bit-set 0.11.1: Apache-2.0 OR MIT
- bit-vec 0.10.2: Apache-2.0 OR MIT
- bitflags 2.13.2: MIT OR Apache-2.0
- borsh 1.8.1: MIT OR Apache-2.0
- borsh-derive 1.8.1: Apache-2.0
- byteorder 1.5.0: Unlicense OR MIT
- bytes 1.12.1: MIT
- cc 1.12.0: MIT OR Apache-2.0
- cesu8 1.1.0: Apache-2.0/MIT
- cfg-if 1.0.5: MIT OR Apache-2.0
- cfg_aliases 0.2.2: MIT
- chacha20 0.10.2: MIT OR Apache-2.0
- combine 4.6.9: MIT
- cpufeatures 0.3.1: MIT OR Apache-2.0
- crc32fast 1.5.6: MIT OR Apache-2.0
- crossbeam-deque 0.8.8: MIT OR Apache-2.0
- crossbeam-epoch 0.9.21: MIT OR Apache-2.0
- crossbeam-utils 0.8.23: MIT OR Apache-2.0
- crunchy 0.2.4: MIT
- derive-new 0.7.0: MIT
- downcast-rs 2.0.2: MIT OR Apache-2.0
- dyn-clone 1.0.20: MIT OR Apache-2.0
- dyn-hash 1.0.0: MIT OR Apache-2.0
- either 1.19.0: MIT OR Apache-2.0
- equivalent 1.0.3: Apache-2.0 OR MIT
- erased-serde 0.4.11: MIT OR Apache-2.0
- errno 0.3.15: MIT OR Apache-2.0
- filetime 0.2.30: MIT/Apache-2.0
- find-msvc-tools 0.1.16: MIT OR Apache-2.0
- flate2 1.1.11: MIT OR Apache-2.0
- float-ord 0.3.2: MIT / Apache-2.0
- foldhash 0.2.0: Zlib
- getrandom 0.4.3: MIT OR Apache-2.0
- half 2.7.1: MIT OR Apache-2.0
- hashbrown 0.16.1: MIT OR Apache-2.0
- hashbrown 0.17.2: MIT OR Apache-2.0
- indexmap 2.14.2: Apache-2.0 OR MIT
- inventory 0.3.25: MIT OR Apache-2.0
- itertools 0.15.0: MIT OR Apache-2.0
- itoa 1.0.19: MIT OR Apache-2.0
- jni 0.21.1: MIT/Apache-2.0
- jni-sys 0.3.1: MIT OR Apache-2.0
- jni-sys 0.4.1: MIT OR Apache-2.0
- jni-sys-macros 0.4.1: MIT OR Apache-2.0
- lazy_static 1.5.1: MIT OR Apache-2.0
- libc 0.2.191: MIT OR Apache-2.0
- libm 0.2.16: MIT
- linux-raw-sys 0.12.1: Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT
- lock_api 0.5.0: MIT OR Apache-2.0
- log 0.4.35: MIT OR Apache-2.0
- maplit 1.0.2: MIT/Apache-2.0
- matrixmultiply 0.3.11: MIT/Apache-2.0
- memchr 2.8.3: Unlicense OR MIT
- memmap2 0.9.12: MIT OR Apache-2.0
- metrics 0.24.6: MIT
- minijinja 3.1.0: Apache-2.0
- miniz_oxide 0.9.1: MIT OR Zlib OR Apache-2.0
- ndarray 0.17.2: MIT OR Apache-2.0
- nom 8.0.0: MIT
- nom-language 0.1.0: MIT
- num-bigint 0.4.8: MIT OR Apache-2.0
- num-complex 0.4.6: MIT OR Apache-2.0
- num-integer 0.1.47: MIT OR Apache-2.0
- num-rational 0.4.2: MIT OR Apache-2.0
- num-traits 0.2.19: MIT OR Apache-2.0
- once_cell 1.21.4: MIT OR Apache-2.0
- parking_lot 0.13.0: MIT OR Apache-2.0
- parking_lot_core 0.10.0: MIT OR Apache-2.0
- pastey 0.2.3: MIT OR Apache-2.0
- portable-atomic 1.15.0: Apache-2.0 OR MIT
- portable-atomic-util 0.2.8: Apache-2.0 OR MIT
- primal-check 0.3.4: MIT OR Apache-2.0
- proc-macro-crate 3.5.0: MIT OR Apache-2.0
- proc-macro2 1.0.107: MIT OR Apache-2.0
- prost 0.14.6: Apache-2.0
- prost-derive 0.14.6: Apache-2.0
- quote 1.0.47: MIT OR Apache-2.0
- r-efi 6.0.0: MIT OR Apache-2.0 OR LGPL-2.1-or-later
- rand 0.10.4: MIT OR Apache-2.0
- rand_core 0.10.1: MIT OR Apache-2.0
- rand_distr 0.6.0: MIT OR Apache-2.0
- rapidhash 4.5.1: MIT OR Apache-2.0
- rawpointer 0.2.1: MIT/Apache-2.0
- rayon 1.12.0: MIT OR Apache-2.0
- rayon-core 1.13.0: MIT OR Apache-2.0
- realfft 3.5.1: MIT
- redox_syscall 0.9.5: MIT
- rustfft 6.4.1: MIT OR Apache-2.0
- rustix 1.1.6: Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT
- rustversion 1.0.23: MIT OR Apache-2.0
- same-file 1.0.6: Unlicense/MIT
- scopeguard 1.2.0: MIT OR Apache-2.0
- serde 1.0.229: MIT OR Apache-2.0
- serde_core 1.0.229: MIT OR Apache-2.0
- serde_derive 1.0.229: MIT OR Apache-2.0
- serde_json 1.0.154: MIT OR Apache-2.0
- shlex 2.0.1: MIT OR Apache-2.0
- simd-adler32 0.3.10: MIT
- smallvec 1.16.6: MIT OR Apache-2.0
- strength_reduce 0.2.4: MIT OR Apache-2.0
- string-interner 0.20.0: MIT/Apache-2.0
- syn 2.0.119: MIT OR Apache-2.0
- syn 3.0.10: MIT OR Apache-2.0
- tar 0.4.46: MIT OR Apache-2.0
- thiserror 1.0.69: MIT OR Apache-2.0
- thiserror-impl 1.0.69: MIT OR Apache-2.0
- toml_datetime 1.1.3+spec-1.1.0: MIT OR Apache-2.0
- toml_edit 0.25.21+spec-1.1.0: MIT OR Apache-2.0
- toml_parser 1.1.7+spec-1.1.0: MIT OR Apache-2.0
- tract-core 0.23.10: MIT OR Apache-2.0
- tract-data 0.23.10: MIT OR Apache-2.0
- tract-extra 0.23.10: MIT OR Apache-2.0
- tract-hir 0.23.10: MIT OR Apache-2.0
- tract-linalg 0.23.10: MIT OR Apache-2.0
- tract-nnef 0.23.10: MIT OR Apache-2.0
- tract-onnx 0.23.10: MIT OR Apache-2.0
- tract-onnx-opl 0.23.10: MIT OR Apache-2.0
- tract-transformers 0.23.10: MIT OR Apache-2.0
- transpose 0.2.3: MIT OR Apache-2.0
- typeid 1.0.3: MIT OR Apache-2.0
- unicode-ident 1.0.27: (MIT OR Apache-2.0) AND Unicode-3.0
- walkdir 2.5.0: Unlicense/MIT
- winapi-util 0.1.11: Unlicense OR MIT
- windows-link 0.2.1: MIT OR Apache-2.0
- windows-sys 0.45.0: MIT OR Apache-2.0
- windows-sys 0.61.2: MIT OR Apache-2.0
- windows-targets 0.42.2: MIT OR Apache-2.0
- windows_aarch64_gnullvm 0.42.2: MIT OR Apache-2.0
- windows_aarch64_msvc 0.42.2: MIT OR Apache-2.0
- windows_i686_gnu 0.42.2: MIT OR Apache-2.0
- windows_i686_msvc 0.42.2: MIT OR Apache-2.0
- windows_x86_64_gnu 0.42.2: MIT OR Apache-2.0
- windows_x86_64_gnullvm 0.42.2: MIT OR Apache-2.0
- windows_x86_64_msvc 0.42.2: MIT OR Apache-2.0
- winnow 1.0.5: MIT
- xattr 1.7.0: MIT OR Apache-2.0
- zerocopy 0.8.66: BSD-2-Clause OR Apache-2.0 OR MIT
- zerocopy-derive 0.8.66: BSD-2-Clause OR Apache-2.0 OR MIT
- zmij 1.0.23: MIT

Word-timed lyrics (1.17): `engine/lyrics-aligner` is a Rust port of schufo/lyrics-aligner (https://github.com/schufo/lyrics-aligner, commit 72be8a1), the method of K. Schulze-Forster, C. Doire, G. Richard and R. Badeau, "Phoneme Level Lyrics Alignment and Text-Informed Singing Voice Separation", IEEE/ACM TASLP 29 (2021), doi:10.1109/TASLP.2021.3091817. Code and model weights: MIT License, © 2021 Kilian Schulze-Forster ([lyrics-aligner-LICENSE](lyrics-aligner-LICENSE)); the port keeps that license. The upstream README also says "All rights reserved"; the repository's MIT LICENSE is what grants reuse, so keep this notice. Bundled models `app/src/main/assets/lyrics/scores.onnx` (sha256 c281e263875ea8f854f1e28245ded24d896a42a1ce56b08412ea43b9bcf403e9) and `voice.onnx` (sha256 f3f24482b7a8e279e5f04c34acd22b69ce74de33d26ca9e8d1ba81b158330439) are ONNX exports of the upstream weights, MIT. The weights were trained on MUSDB18 and its lyrics extension; check the MUSDB18 terms before any commercial release. Pronunciations: `app/src/main/assets/lyrics/cmudict-nostress.dict` (copied at runtime to `cmudict-nostress.txt.gz` for the engine) is derived from the CMU Pronouncing Dictionary, © 1993-2015 Carnegie Mellon University, BSD-style license ([CMUdict-LICENSE](CMUdict-LICENSE)); binary redistribution must reproduce that notice, so the app ships it in `assets/lyrics/NOTICE.txt`. Inference uses tract (sonos/tract, MIT OR Apache-2.0), listed above. Lyrics dictation uses the phone's own speech recognizer (Android `SpeechRecognizer`, on-device when available); no speech library is bundled.

AI background sharpening: LiteRT 2.3.0 (`com.google.ai.edge.litert:litert`, `litert-gpu`, `litert-npu-runtime-google-tensor`), Apache License 2.0, Google. Bundled model `app/src/main/assets/models/real_esrgan_general_x4v3.tflite` (sha256 47dbfe34c3492e6f7f7fb2b0ed99794e8e41215c2177821f03e0f5304e4b9846): Real-ESRGAN-General-x4v3 by Xintao Wang et al. (https://github.com/xinntao/Real-ESRGAN), BSD 3-Clause License; LiteRT float conversion from Qualcomm AI Hub (huggingface.co/qualcomm/Real-ESRGAN-General-x4v3, release v0.67.0), distributed under the original model's BSD 3-Clause license.

The crown and demo music were provided by the user for this private build.

Ideas and algorithms used without copying code: the circle-of-fifths key colors follow dedo1911/music-visualizer (MIT); `BeatFx.hash01` is the "lowbias32" integer hash by Chris Wellons (public domain); the ripple on hits (onsets drive radial ripples) and the rainbow edge driven by treble and spectral centroid follow baditaflorin/synaesthete (MIT); drop finding (a short window after a moment compared with a longer one before it) follows the "drop impact" idea of le-director/director-signals (MIT), and the flash and wave on drops and the build-up before them (drawing inward and darkening) follow 5ymph0en1x/Sythm (MIT); the bounce on beats is the classic squash-and-stretch animation principle, in the "stretch as the beat comes, squash on it, rebound" order described for the Bounce move in the IKANDY user guide; the sound blob (a circular spectrum shape that pulses with the bass) follows ntrogh/spectrum-visualizer (MIT) and Pahiro/avs (MIT), and the star warp pushed by the music follows the starfield effect of slastra/hyprglaze (MIT); the sound wave line and ring start on a rising zero crossing, the edge trigger described by cat2151/cat-oscilloscope (MIT) and alemidev/scope-tui (MIT).
