# AI background sharpening (1.13.0)

The **Sharpen & clean up background (AI)** switch runs Real-ESRGAN-General-x4v3 on background photos and videos. It is a 4x super-resolution model trained on noisy, compressed pictures, so it also cleans up noise and compression blocks. It runs through LiteRT 2.3.0's `CompiledModel` API (`AiUpscale.kt`).

## Which chip

1. **TPU (Tensor G5 NPU).** Used only when `app/src/main/assets/models/real_esrgan_general_x4v3_Google_Tensor_G5.tflite` exists. The Google Tensor SDK has no on-device (JIT) compilation, so the model must be compiled ahead of time by `scripts/aot_compile_g5.py`. The app times one tile at startup. If it is slower than 150 ms, the "NPU" run has silently fallen back to the CPU, and the app moves on to the GPU.
2. **GPU.** The bundled `real_esrgan_general_x4v3.tflite` through `litert-gpu`.
3. **CPU.** The same file, as a last resort.

The chip used is logged (`adb logcat -s SoundWaive`) and named in the export-ready box ("Background sharpened by AI on the TPU/GPU/CPU."). If no chip can load the model, the effect is skipped with a log line, and exports are unaffected.

## What it does

- **Photos:** processed once, when imported or when the switch is turned on. The result is saved as `files/background-ai.png` and reused by the preview and every export. It only runs when the photo is smaller than 1080 px on its short side; bigger photos are already sharp enough.
- **Videos:** processed during export only, on frames smaller than the export's background size (Render quality short side, capped at the output). Each decoded frame is copied to the CPU once (the existing read-back), cut into 128 × 128 tiles that overlap by 8 px, and run through the model. The upscaled frame is cached as a JPEG in `cache/ai-video/<clip>/`, and only the newest clip is kept, so re-exports read the cache. The preview shows plain frames. A video export with the switch on uses the Canvas background path, so 1.12's zero-copy video texture is bypassed for that export. LiteRT's Kotlin API takes CPU float arrays, so there is no hardware-buffer path into the model from Kotlin.
- **Cost (not measured yet):** Christian's research puts EDSR-x4 at about 23 ms on a Pixel 8a-class GPU; expect something similar per tile here. A 480p frame for a 1080p export is about 40 tiles, so about 1 s per frame on the GPU. The TPU should be several times faster. The JPEG cache makes the second export of the same clip close to the 1.12 speed.

## Compiling for the Tensor G5 (once, with Tensor SDK beta access)

```
python3 -m venv .venv && . .venv/bin/activate
pip install "ai-edge-litert==2.3.0" ai-edge-litert-sdk-google-tensor
GOOGLE_TENSOR_BACKEND_ENABLED=1 python3 scripts/aot_compile_g5.py
```

Then rebuild and reinstall. The report should say every op was offloaded ("fully compiled"). The model ends in a PixelShuffle (`DEPTH_TO_SPACE`). If that op is left on the CPU, re-export the model with PixelShuffle rewritten, for example as a transposed convolution, so the whole graph stays on the TPU.

## Pitfalls

- **Versions must match.** `litert`, `litert-gpu`, `litert-npu-runtime-google-tensor` (which supplies `libLiteRtDispatch_GoogleTensor.so`) and the Python `ai-edge-litert` used for AOT must all be 2.3.0. A mismatch shows up in two ways. With `CompiledModel.Options(Accelerator.NPU)` it loads but silently runs on the CPU (hundreds of ms per run); the app detects this and uses the GPU. The stricter set-based options can crash natively in `TfLiteOpaqueContextReplaceNodeSubsetsWithDelegateKernels` (LiteRT issue #7787). Bump all four together.
- **`GOOGLE_TENSOR_BACKEND_ENABLED=1`.** Stable Python packages only register the Google Tensor backend with this set. Without it, compiling fails with `Unsupported backend id: GOOGLE`.
- **Kotlin metadata.** LiteRT 2.3.0's Kotlin API is compiled with metadata 2.4. Android Gradle Plugin 9.4 ships Kotlin 2.2, which can only read metadata up to 2.3, so `app/build.gradle.kts` passes `-Xskip-metadata-version-check` (same flag as Google's LiteRT samples).
- **edgetpu_app_service error 16.** The Tensor TPU service rejects apps that are not on its allowlist, which is tied to the package name and signing certificate. The NPU load then fails with this error, and the app falls back to the GPU. The beta allowlist is per package and signature, so the debug build (`com.parker.crownpulse.debug`, debug key) and the release build count as different apps. Register the one you install.
- **Android 12+ and arm64 only.** NPU support needs API 31, so the app's minSdk is now 31 and it ships arm64-v8a only. Native libraries are extracted (`useLegacyPackaging`) so the dispatch library can be found on disk, and `.tflite` assets are stored uncompressed.
