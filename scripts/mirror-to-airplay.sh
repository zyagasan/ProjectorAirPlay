#!/bin/bash
# プロジェクターの再生音を scrcpy で取得し、AirMac Express へ RAOP で送る(Mac 経由の動作確認用)。
# 使い方: mirror-to-airplay.sh [秒数(既定 30)] [AirPlay機器IP]
SEC=${1:-30}; IP=${2:-192.0.2.10}
export ADB="/Applications/platform-tools 2/adb"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
FIFO=$(mktemp -u /tmp/capfifo.XXXXXX); mkfifo "$FIFO"
trap 'kill $(jobs -p) 2>/dev/null; rm -f "$FIFO"' EXIT
scrcpy --no-video --no-playback --audio-source=${SRC:-output} --audio-codec=raw --time-limit="$SEC" --record="$FIFO" --record-format=wav >/dev/null 2>&1 &
ffmpeg -loglevel error -fflags nobuffer -flags low_delay -flush_packets 1 -probesize 32 -analyzeduration 0 -i "$FIFO" ${GAIN:+-af volume=$GAIN} -f s16le -ar 44100 -ac 2 - | tee ${TEE:-/dev/null} \
 | DYLD_LIBRARY_PATH=/opt/homebrew/opt/openssl@3/lib "$DIR/third_party/libraop/bin/cliraop-macos-arm64" -p 7000 -t 4 -l ${LAT:-8820} -v $(( ${VOL:-40} > 50 ? 50 : ${VOL:-40} )) -d 3 "$IP" -
