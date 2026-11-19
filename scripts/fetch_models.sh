#!/usr/bin/env bash
# Downloads the bundled AI upscaling model (Real-ESRGAN-General-x4v3, float LiteRT build
# from Qualcomm AI Hub, BSD-3-Clause) into app/src/main/assets/models and checks its hash.
# The model is already committed; use this to restore or re-check it.
set -euo pipefail
cd "$(dirname "$0")/.."
URL="https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_general_x4v3/releases/v0.67.0/real_esrgan_general_x4v3-tflite-float.zip"
SHA="47dbfe34c3492e6f7f7fb2b0ed99794e8e41215c2177821f03e0f5304e4b9846"
OUT="app/src/main/assets/models/real_esrgan_general_x4v3.tflite"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/model.zip" "$URL"
unzip -q -o "$tmp/model.zip" -d "$tmp"
mkdir -p "$(dirname "$OUT")"
cp "$tmp/real_esrgan_general_x4v3-tflite-float/real_esrgan_general_x4v3.tflite" "$OUT"
echo "$SHA  $OUT" | sha256sum -c -
