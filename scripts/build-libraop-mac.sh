#!/bin/bash
# macOS(arm64)で libraop の cliraop をビルドする。Homebrew の openssl@3 が必要。
set -e
cd "$(dirname "$0")/.."
[ -d third_party/libraop ] || git clone --recursive --depth 1 https://github.com/philippe44/libraop third_party/libraop
cd third_party/libraop
git apply --check ../../patches/libraop-cliraop.patch 2>/dev/null && git apply ../../patches/libraop-cliraop.patch
make CC=clang HOST=macos PLATFORM=arm64 LDFLAGS="-L/opt/homebrew/opt/openssl@3/lib -lc++" || true  # 最後の lipo エラーは無視してよい
ls -la bin/cliraop-macos-arm64
