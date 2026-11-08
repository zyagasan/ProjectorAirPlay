# ProjectorAirPlay

プロジェクターの音を、AirPlay スピーカーで鳴らすための仕組みです。
間に Raspberry Pi を置くだけで、Netflix や YouTube の音がスピーカーから出ます。

```text
プロジェクター  ──Bluetooth──▶  Raspberry Pi  ──AirPlay──▶  スピーカー
```

プロジェクターから見ると、Pi は普通の Bluetooth スピーカーです。だから、どのアプリの音でも送れます。

## できること
- **Netflix も使える**: アプリを選ばず、音を送れます。
- **映像と音が合う**: 音が遅れる分だけ、映像も自動で遅らせます。
- **音が途切れにくい**: Bluetooth と AirPlay のわずかな速度の差を、自動で調整します。
- **スマホで操作できる**: ブラウザから音量や送信先を変更できます。
- **リモコンも使える**: プロジェクターのリモコンの音量ボタンで、スピーカーの音量が変わります。

## 必要なもの
| 機器 | 動作を確認した機種 |
|---|---|
| Bluetooth で音を出せるプロジェクター | FORMOVIE / Xiaomi L1 Pro(C045RGN) |
| Raspberry Pi | Raspberry Pi 3 Model B+ |
| AirPlay 1 に対応したスピーカーや受信機 | AirMac Express |

## 使い方
1. Pi に Bluetooth の受信ソフトを準備します。手順は[設計資料の「実装」](hardware/DESIGN.md)を見てください。
2. このリポジトリを Pi に置き、中のフォルダーで次のコマンドを実行します。

   ```bash
   ./hardware/bridge/install.sh
   ```

3. 同じ LAN のスマホや PC で、`http://<Pi のホスト名>.local:8590/` を開きます。
4. 画面で送信先のスピーカーを選びます。
5. 画面の「ペアリング」を押し、3 分以内にプロジェクターの Bluetooth 設定から「AirPlay Bridge」につなぎます。

次回からは、プロジェクターがつなぎ直すと自動で音が出ます。

## 仕組み
![システム構成図](docs/system-diagram.svg)

## 安全に使うために
管理画面にログインはありません。**家の LAN の中だけで使い、ルーターのポート開放はしないでください。**
詳しくは [SECURITY.md](SECURITY.md) を見てください。

## フォルダーの中身
| 場所 | 内容 |
|---|---|
| `hardware/bridge/` | Pi で動くプログラム(今の方式) |
| `hardware/DESIGN.md` | 設計資料 |
| `android/` | 以前のプロジェクター内蔵アプリ方式。Netflix とは併用できません |
| `scripts/`, `patches/` | 検証用のスクリプトと、libraop への変更 |

## ライセンス
[MIT ライセンス](LICENSE)です。利用しているソフトウェアは [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) に載せています。
