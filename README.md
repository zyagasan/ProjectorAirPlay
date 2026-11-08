# ProjectorAirPlay

Google TV プロジェクターの音を、AirPlay 1(RAOP)対応のスピーカーへ飛ばすプロジェクトです。
Raspberry Pi が Bluetooth で音を受けて AirPlay へ送るので、Netflix のような DRM のあるアプリでも使えます。

![システム構成図](docs/system-diagram.svg)

## 特長
- **アプリを選ばない**: プロジェクターから見ると普通の Bluetooth スピーカーです。
- **映像と音が合う**: 音の遅れを Bluetooth で申告し、プロジェクターが映像を同じだけ遅らせます。
- **途切れにくい**: バッファと可変レート変換で、Bluetooth と AirPlay の時計のずれを吸収します。
- **操作しやすい**: Web 画面から音量、ずれ補正、送信先を変更できます。リモコンの音量ボタンとも連動します。

## 動作確認した環境
| 機器 | 内容 |
|---|---|
| プロジェクター | FORMOVIE / Xiaomi L1 Pro(C045RGN、Android 14) |
| 中継機 | Raspberry Pi 3 Model B+(Debian 13、有線 LAN) |
| 受信機 | AirMac Express(AirPlay 1) |

## 導入
BlueALSA のビルドや Bluetooth の設定が先に必要です。手順と注意点は [hardware/DESIGN.md](hardware/DESIGN.md) を参照してください。
準備ができたら、Pi 上で次を実行します。

```bash
~/airplay-bridge/bridge/install.sh
```

`http://<Pi のホスト名>.local:8590/` を開き、送信先を選びます。プロジェクターをつなぐときは、
Web 画面の「ペアリング」を押すと 3 分間だけ受付を開きます。

## セキュリティ
Web 画面に認証はありません。信頼できる宅内 LAN だけで使い、インターネットへ公開しないでください。
詳しくは [SECURITY.md](SECURITY.md) を参照してください。

## ライセンス
同梱・利用しているソフトウェアは [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) を参照してください。

---

# 開発の記録

ここから下は、Bluetooth 方式に移る前の検証記録です。

## 方式
shell 権限(uid 2000)のプロセスが `CAPTURE_AUDIO_OUTPUT` を使って `REMOTE_SUBMIX` で再生音を録音し、
ローカルソケット経由でメインアプリへ渡し、RAOP で送信する。

```
[shell権限 app_process: AudioRecord REMOTE_SUBMIX] -> localhost socket -> [アプリ: RAOP送信] -> AirPlay機器
```

## 検証済み(2026-10-20)
- `com.android.shell` に `CAPTURE_AUDIO_OUTPUT: granted=true`
- scrcpy 5.0.1 の `--audio-source=output` / `playback` で YouTube 再生音を録音できた
- 端末 ABI は armeabi-v7a(32bit)。NDK ビルドは v7a 必須
- AirPlay 機器は mDNS `_raop._tcp` で検出できる(`メインスピーカー`)

## Mac -> AirMac Express の RAOP 送信(確認済み 2026-10-20)
`scripts/build-libraop-mac.sh` でビルドし、`scripts/send-test-tone.sh` でテスト音を送る。実機で再生を確認した。
つまずき点:
- AirMac Express は **port 7000**(cliraop の既定は 5000)。`-p 7000` が必要
- `-t 4`(MFi, et=4)が必要。cliraop は `-t` の値を `am` に入れていたため修正(`patches/`)
- macOS 標準の libcrypto だと abort する。`DYLD_LIBRARY_PATH` で Homebrew の openssl@3 を指定
- `-v 20` + 小さい振幅だと聞こえないほど小さい。`-v 60` で確認できた。遅延は約1.25秒

## Mac 経由の中継(確認済み 2026-10-20)
`scripts/mirror-to-airplay.sh` で、プロジェクターの再生音(scrcpy)を AirMac Express へ送って再生できた。
`SRC=playback VOL=50 GAIN=1.6 scripts/mirror-to-airplay.sh 25`
- **RAOP 音量は 50 以下**(スクリプトと cliraop パッチで上限 50。100 は大きすぎる)
- `output`(REMOTE_SUBMIX)は取得レベルが約 -42 dB と小さく、`playback` は約 -16 dB。音量は端末側設定に依存する可能性
- stdin 入力では **最初の音を受け取ってから RAOP 接続する**必要あり(接続後に無音期間があると、音が遅れて捨てられ無音になる)。パッチで対応
- パイプで 1 回の read が短いと音が途切れるため、1 チャンク分読み切るようにパッチ済み

## 遅延の短縮(2026-10-20)
- RAOP 側: 既定 約1250ms → `RAOP_LATENCY_MIN=4410` + `-l 4410` で **199ms**(AirMac Express は受け付ける。途切れなし)
- 起動時にたまった音(スクリプト起動〜最初の読み取りの間)を cliraop が捨てる(これが残ると恒久的な遅延になる)
- ffmpeg 出力を `-flush_packets 1` で即時送出
- 体感は「前より良いがまだ気になる」。残りは Android 側の取得遅延、Mac 中継(Wi-Fi 2 往復)、AirMac 内部バッファの可能性
- 端末単体アプリにすれば Mac 中継が不要になる

## Android アプリ(2026-10-20、動作確認済み)
`android/` に Android TV アプリを作成。プロジェクター単体で「再生音の取得 → AirMac Express へ送信」まで動き、
Mac 経由より遅延が減ったことを確認した。詳細は [android/README.md](android/README.md)。

## 外部デバイス方式(2026-11-07〜)
アプリ方式は Netflix と両立できないため、Bluetooth で音を受けて AirPlay へ送る外部デバイス(Raspberry Pi)方式を作成した。
[hardware/DESIGN.md](hardware/DESIGN.md)

## 次のステップ
1. (済) Mac から AirMac Express へ PCM を RAOP 送信するテスト(libraop)
2. キャプチャ用プロセス(Java, app_process)
3. Android アプリ(Kotlin, mDNS 検索, Leanback UI, adb 接続)

## 検証コマンド
```
scrcpy --no-video --no-playback --audio-source=output --audio-codec=raw --time-limit=10 --record=cap.wav
ffmpeg -i cap.wav -af volumedetect -f null -
```
