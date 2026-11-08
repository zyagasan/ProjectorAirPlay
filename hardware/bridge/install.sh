#!/bin/bash
# Raspberry Pi 上で実行する。エンジンをビルドし、管理デーモンを systemd に登録して起動する。
set -euo pipefail
cd "$(dirname "$0")"
BASE=$(cd .. && pwd)
make -C engine
sed -e "s|@USER@|$(id -un)|g" -e "s|@BASE@|$BASE|g" systemd/airplay-bridge.service \
  | sudo tee /etc/systemd/system/airplay-bridge.service >/dev/null
sudo systemctl daemon-reload
sudo systemctl enable --now airplay-bridge.service
sudo systemctl restart airplay-bridge.service
echo "Web: http://$(hostname).local:8590/"
