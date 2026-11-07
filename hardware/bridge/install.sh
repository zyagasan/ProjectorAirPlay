#!/bin/bash
# Raspberry Pi 上で実行する。エンジンをビルドし、管理デーモンを systemd に登録して起動する。
set -e
cd "$(dirname "$0")"
make -C engine
sudo cp systemd/airplay-bridge.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now airplay-bridge.service
echo "Web: http://$(hostname).local:8590/"
