#!/bin/bash
# AirMac Express「メインスピーカー」へテスト音を送る。使い方: send-test-tone.sh [IP]
IP=${1:?AirPlay 機器の IP を指定してください}
ffmpeg -loglevel error -y -f lavfi -i "sine=frequency=440:duration=8:sample_rate=44100" -af volume=0.6 -f s16le -ac 2 -ar 44100 /tmp/tone.raw
# AirMac Express は port 7000 / et=4。標準の libcrypto だと abort するため Homebrew の OpenSSL を指定する
DYLD_LIBRARY_PATH=/opt/homebrew/opt/openssl@3/lib \
  "$(dirname "$0")/../third_party/libraop/bin/cliraop-macos-arm64" -p 7000 -t 4 -v 40 -d 3 "$IP" /tmp/tone.raw
