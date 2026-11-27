# Third-party notices

Kotlin standard library: Apache License 2.0, JetBrains. Gradle wrapper: Apache License 2.0, Gradle. Android runtime components are supplied by the device.

The 32-bit Intel build includes the LiteRT 1.4.2 CPU interpreter from Google. Its distribution license is included in [LiteRT-1.4.2-LICENSE](LiteRT-1.4.2-LICENSE).

Rust dependency lockfile includes the following packages. Platform-specific or build-only entries may not be present in the Android binary. License texts are included in this directory.

- bytes 1.12.1: MIT
- cesu8 1.1.0: Apache-2.0/MIT
- cfg-if 1.0.5: MIT OR Apache-2.0
- combine 4.6.9: MIT
- jni 0.21.1: MIT/Apache-2.0
- jni-sys 0.3.1: MIT OR Apache-2.0
- jni-sys 0.4.1: MIT OR Apache-2.0
- jni-sys-macros 0.4.1: MIT OR Apache-2.0
- log 0.4.35: MIT OR Apache-2.0
- memchr 2.8.3: Unlicense OR MIT
- proc-macro2 1.0.107: MIT OR Apache-2.0
- quote 1.0.47: MIT OR Apache-2.0
- same-file 1.0.6: Unlicense/MIT
- syn 2.0.119: MIT OR Apache-2.0
- thiserror 1.0.69: MIT OR Apache-2.0
- thiserror-impl 1.0.69: MIT OR Apache-2.0
- unicode-ident 1.0.27: (MIT OR Apache-2.0) AND Unicode-3.0
- walkdir 2.5.0: Unlicense/MIT

AI background sharpening: LiteRT 2.3.0 (`com.google.ai.edge.litert:litert`, `litert-gpu`, `litert-npu-runtime-google-tensor`), Apache License 2.0, Google. Bundled model `app/src/main/assets/models/real_esrgan_general_x4v3.tflite` (sha256 47dbfe34c3492e6f7f7fb2b0ed99794e8e41215c2177821f03e0f5304e4b9846): Real-ESRGAN-General-x4v3 by Xintao Wang et al. (https://github.com/xinntao/Real-ESRGAN), BSD 3-Clause License; LiteRT float conversion from Qualcomm AI Hub (huggingface.co/qualcomm/Real-ESRGAN-General-x4v3, release v0.67.0), distributed under the original model's BSD 3-Clause license.

The crown and demo music were provided by the user for this private build.

Ideas and algorithms used without copying code: the circle-of-fifths key colors follow dedo1911/music-visualizer (MIT); `BeatFx.hash01` is the "lowbias32" integer hash by Chris Wellons (public domain); the ripple on hits (onsets drive radial ripples) and the rainbow edge driven by treble and spectral centroid follow baditaflorin/synaesthete (MIT); drop finding (a short window after a moment compared with a longer one before it) follows the "drop impact" idea of le-director/director-signals (MIT), and the flash and wave on drops and the build-up before them (drawing inward and darkening) follow 5ymph0en1x/Sythm (MIT); the bounce on beats is the classic squash-and-stretch animation principle, in the "stretch as the beat comes, squash on it, rebound" order described for the Bounce move in the IKANDY user guide; the sound blob (a circular spectrum shape that pulses with the bass) follows ntrogh/spectrum-visualizer (MIT) and Pahiro/avs (MIT), and the star warp pushed by the music follows the starfield effect of slastra/hyprglaze (MIT); the sound wave line and ring start on a rising zero crossing, the edge trigger described by cat2151/cat-oscilloscope (MIT) and alemidev/scope-tui (MIT).
