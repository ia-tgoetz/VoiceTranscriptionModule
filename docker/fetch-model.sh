#!/usr/bin/env bash
# Downloads a whisper.cpp ggml model for local testing. Production / air-gapped gateways: download once on
# a connected machine, verify the checksum, and copy the file to <ignition>/data/voicetranscription/models/.
#   ./fetch-model.sh            -> ggml-tiny.bin  (75 MB, multilingual - used by the test-suite)
#   ./fetch-model.sh base       -> ggml-base.bin  (142 MB, multilingual - module default)
#   ./fetch-model.sh base.en    -> ggml-base.en.bin (English-only)
set -euo pipefail
MODEL="${1:-tiny}"
DIR="$(cd "$(dirname "$0")" && pwd)/models"
mkdir -p "$DIR"
URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-${MODEL}.bin"
OUT="$DIR/ggml-${MODEL}.bin"
if [[ -s "$OUT" ]]; then
  echo "Already present: $OUT"
  exit 0
fi
echo "Downloading $URL"
curl -fL --retry 3 -o "$OUT.part" "$URL"
mv "$OUT.part" "$OUT"
echo "Saved $OUT ($(du -h "$OUT" | cut -f1))"
