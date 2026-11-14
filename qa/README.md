# Android verification fixture

SmokeInstrumentation.kt is the independent integration runner used for this build. It is not included in the production APK. Compile it only into a debuggable QA variant and register it as an instrumentation runner targeting com.parker.crownpulse. It expects the private fixture losingmymindvlc.flac (261.47 seconds) at app files/qa-input.flac. That song is deliberately not distributed with this source package.

The runner exercises demo loading, all preset renders, Android FLAC decoding, Rust JNI analysis, settings round-trip, symbol/color locks, portrait AVC/AAC export and landscape export. It saves screenshots, videos, progress and result files under app files/qa. Run it asynchronously with adb shell am instrument com.parker.crownpulse/com.parker.crownpulse.SmokeInstrumentation, then read result.txt.

Rust signal-processing unit tests can run independently with cargo test --locked in engine/. See the delivered Verification.md for observed results and limits.

The `-e mode audio` runner in AudioTimingCheck.kt reuses the existing rendered clips and tests AAC priming/tail correction, 48 kHz mono input, and native Android re-import of the corrected MP4. Supply a one-second 48 kHz mono PCM16 fixture at app files/qa48.pcm. `-e mode stills` renders the final crown tint/texture in white and cyan.

The `-e mode art` fixture launches the activity and verifies image decoding, app-local artwork persistence, and selecting Custom artwork via the same import handler used by the file picker.
