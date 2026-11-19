#!/usr/bin/env python3
"""Compiles the bundled upscaling model ahead of time for the Pixel 10's Tensor G5 TPU.

Run once on a Linux x86_64 machine with Google Tensor SDK (beta) access:

    python3 -m venv .venv && . .venv/bin/activate
    pip install "ai-edge-litert==2.3.0" ai-edge-litert-sdk-google-tensor   # same version as the app
    GOOGLE_TENSOR_BACKEND_ENABLED=1 python3 scripts/aot_compile_g5.py

It writes app/src/main/assets/models/real_esrgan_general_x4v3_Google_Tensor_G5.tflite.
The app uses that file on the TPU when it is present and falls back to the GPU model
otherwise. Rebuild the app after running this. The compiled model must come from the same
LiteRT version as the app's com.google.ai.edge.litert dependencies (2.3.0); see
docs/AI-UPSCALE.md for the pitfalls.
"""

import os
import pathlib
import shutil
import sys
import tempfile

# The Google Tensor backend is only registered with this set (stable packages).
os.environ.setdefault("GOOGLE_TENSOR_BACKEND_ENABLED", "1")

from ai_edge_litert.aot import aot_compile as aot  # noqa: E402
from ai_edge_litert.aot.vendors.google_tensor import target as gt  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODELS = ROOT / "app" / "src" / "main" / "assets" / "models"
SOURCE = MODELS / "real_esrgan_general_x4v3.tflite"
DEST = MODELS / "real_esrgan_general_x4v3_Google_Tensor_G5.tflite"


def main() -> int:
    if not SOURCE.is_file():
        print(f"Missing {SOURCE}; run scripts/fetch_models.sh first.", file=sys.stderr)
        return 1
    with tempfile.TemporaryDirectory() as out:
        work = pathlib.Path(out) / SOURCE.name
        shutil.copy(SOURCE, work)
        compiled = aot.aot_compile(
            str(work),
            output_dir=out,
            target=[gt.Target(gt.SocModel.TENSOR_G5)],
            keep_going=False,
        )
        print(compiled.compilation_report())
        found = sorted(pathlib.Path(out).rglob("*Google_Tensor_G5*.tflite"))
        if not found:
            # Some versions only keep results in memory; ask the result to write them.
            export = getattr(compiled, "export", None)
            if callable(export):
                export(out)
                found = sorted(pathlib.Path(out).rglob("*Google_Tensor_G5*.tflite"))
        if not found:
            print("The compiler produced no Tensor G5 model; see the report above.", file=sys.stderr)
            return 1
        shutil.copy(found[0], DEST)
    print(f"Wrote {DEST} ({DEST.stat().st_size // 1024} KB). Rebuild and reinstall the app.")
    print("Check the report: every op should be offloaded to the TPU (\"fully compiled\").")
    return 0


if __name__ == "__main__":
    sys.exit(main())
