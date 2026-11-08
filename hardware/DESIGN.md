# 外部デバイス方式 設計書(v0.1 / 2026-11-07)

プロジェクター(FORMOVIE / Xiaomi L1 Pro, Android 14)の音を、**プロジェクターの外に置いた小型デバイス**で受け取り、
AirPlay 1(RAOP)で AirMac Express「メインスピーカー」へ送る。

## 1. なぜ外部デバイスにするか

アプリ方式(`android/`)では、プロジェクター内で再生音を「取得」している。この端末では、取得が動いているだけで
Netflix が `tvq-pb-101` で再生できなくなり、Netflix の音は DRM で取得もできなかった(README 参照)。

外部デバイス方式では、プロジェクターから見ると**普通のスピーカー/ヘッドホンに音を出しているだけ**になる。
取得処理がないので Netflix も含めて全アプリがそのまま動く。

## 2. プロジェクターの音声出力(候補)

| 出力 | 中身 | Netflix | 音質 | 映像との同期(リップシンク) | 判断 |
|---|---|---|---|---|---|
| **Bluetooth (A2DP)** | SBC / AAC(非可逆) | ◎ | ○ | **◎ 補正できる見込み**(遅延の申告) | **第一候補** |
| 3.5mm ヘッドホン出力 | アナログ | ◎ | ○(ADC の質次第) | △ 補正なし | 第二候補(配線が要る) |
| USB(USB オーディオ) | デジタル PCM(可逆) | ◎ | ◎ | △ 補正なし | 端末が USB オーディオ出力に対応しているか**要確認** |
| HDMI ARC | デジタル(PCM/Dolby) | ◎ | ◎ | △ | 受け側に ARC 対応機器が必要で大がかり。見送り |

出典(端子): HDMI 2.1(ARC)×1、USB 2.0×1、3.5mm ヘッドホン出力、Bluetooth 5.0。

### リップシンクが「Bluetooth だと補正できる」理由

Bluetooth の A2DP には、受け側が「自分の再生遅延」を送り手に申告する仕組み(**AVDTP Delay Reporting**)がある。
Android はこの値を使って映像側を遅らせ、音と合わせる(Bluetooth ヘッドホンで口の動きが合うのはこのため)。

外部デバイスが「Bluetooth の受信 + AirPlay 送信 + AirMac の再生」までの合計(例: 400ms)を申告すれば、
**プロジェクターが映像を 400ms 遅らせてくれる** → 遅延があってもずれない。
これはアプリ方式では不可能だった(映像を遅らせる手段がなかった)。

- BlueZ では 2024 年に A2DP 受信側の Delay を書き込めるようにする変更が提案されている。BlueALSA にも codec ごとの
  DelayAdjustment がある。**どの版で入っているか、この端末の Android が申告を反映するかは実機で確認する**(§7)。

## 3. 全体構成(第一候補: Bluetooth ブリッジ)

```
[プロジェクター]                [AirPlay ブリッジ(Raspberry Pi)]                    [AirMac Express]
  Netflix / YouTube など         BlueZ + BlueALSA        PCM 44.1k/16bit                メインスピーカー
  ── Bluetooth A2DP ──────▶  A2DP 受信(SBC/AAC 復号) ──▶ libraop(RAOP 送信)──Wi-Fi──▶  再生
       ◀── 遅延の申告(合計 約 400ms)──                    ・遅延 200ms 設定(検証済み)
                                                               ・音量 0〜100
```

- プロジェクター側は「Bluetooth スピーカー『AirPlay Bridge』とペアリング」するだけ。アプリ不要。
- ブリッジは電源を入れれば自動で待ち受け、接続されたら自動で AirMac へ送る。

## 4. ハードウェア

**既成品の組み合わせで足りる。専用基板は不要**(§8 で将来の小型化案)。

| 部品 | 推奨 | 理由 |
|---|---|---|
| 本体 | **Raspberry Pi 4 Model B(2GB 以上)** または Pi 5 | Bluetooth 5.0 + **5GHz Wi-Fi**。Bluetooth(2.4GHz)と AirMac への Wi-Fi を別の帯域にでき、音切れの原因になる電波の取り合いを避けられる |
| 電源 | 純正 USB-C 電源(Pi 4: 5V 3A / Pi 5: 5V 5A) | プロジェクターの USB 2.0 端子(500mA 程度)では足りない |
| microSD | 16GB 以上 | Raspberry Pi OS Lite(64bit) |
| ケース | 放熱できるもの(金属/ファン付き) | 常時稼働 |
| (任意)USB Bluetooth アダプタ | Bluetooth 5.x(Realtek 系など) | 内蔵 Bluetooth で音切れが出た場合の予備 |

- Pi Zero 2 W は安価だが **2.4GHz Wi-Fi のみ**で、Bluetooth と同じ帯域・同じアンテナを共有するため音切れのリスクが高い。
  AirMac Express(第2世代 A1392 はデュアルバンド)を 5GHz で使える Pi 4/5 を推奨。
- 第二候補(3.5mm 入力)にする場合は追加で **USB オーディオインターフェース(ライン入力付き、48kHz/16bit 以上)**。
  例: Behringer UCA202 相当。グラウンドループのノイズ対策にアイソレーターがあると安心。

## 5. ソフトウェア構成

| 層 | 使うもの | 備考 |
|---|---|---|
| OS | Raspberry Pi OS Lite (64bit, Bookworm 以降) | GUI なし |
| Bluetooth 受信 | BlueZ + **BlueALSA**(`bluealsa -p a2dp-sink --codec=aac`) | AAC が使えれば SBC より高音質 |
| 遅延の申告 | BlueALSA の DelayAdjustment / BlueZ の Delay | 合計遅延を申告(§2) |
| AirPlay 送信 | **このリポジトリの patched libraop**(Linux arm64 でビルド) | Mac・Android で検証済み: port 7000、et=4、遅延 200ms、最初の音が来てから接続 |
| 橋渡し | 小さな常駐プログラム(C か Python) | BlueALSA から PCM を読み、44.1kHz に変換して libraop へ。接続/切断で開始・停止 |
| 音量 | Bluetooth の音量(AVRCP 絶対音量)→ AirPlay 音量 0〜100 | プロジェクターのリモコンの音量ボタンで AirPlay 音量が動く(アプリ方式で苦労した点が自然に解決) |
| 自動起動 | systemd サービス | 電源オンで待ち受け |

- 変換(48k→44.1k)はアプリで作った高品質リサンプラと同じ方式(Kaiser 窓 sinc)を C で実装、または libsamplerate / soxr を使う。
  A2DP が 44.1kHz で来る場合は変換不要。
- AirMac は mDNS(`_raop._tcp`)で自動検出、または設定ファイルで「メインスピーカー」を指定。

## 6. 遅延の見積もり

| 区間 | Bluetooth 方式 | 3.5mm / USB 方式 |
|---|---|---|
| プロジェクター内部 + 無線/ケーブル | 100〜150ms(A2DP 符号化・送信) | 〜10ms |
| ブリッジ内(受信バッファ・変換) | 20〜50ms | 10〜20ms |
| RAOP + AirMac 再生 | 200ms〜(検証済みの下限) | 200ms〜 |
| **合計** | **約 350〜400ms** | **約 230〜260ms** |
| 映像とのずれ | **申告が効けば 0 に補正** | 補正なし(アプリ方式と同程度) |

## 7. 実機で最初に確かめること(試作前)

1. **Android が Bluetooth の遅延申告を反映するか**: 手持ちの Bluetooth ヘッドホン/スピーカーで YouTube を見て
   口の動きが合うか。合えば申告は効いている。`adb shell dumpsys bluetooth_manager` で delay の値も確認。
2. **プロジェクターが AAC で送れるか**(SBC だけだと音質が一段落ちる): 接続後に `dumpsys bluetooth_manager` の codec を確認。
3. **USB オーディオ出力に対応しているか**(USB 方式を候補に残すため): `dumpsys media.audio_policy` に USB の
   出力モジュールがあるか。手持ちの USB DAC を挿して出力先に出るか。
4. Bluetooth と Wi-Fi を同時に使ったときの音切れ(試作機で 30 分連続再生)。

## 8. 将来の小型化・基板設計(必要になったら)

Raspberry Pi で動作と音質・遅延を確定させてから検討する。

- **案 A: Raspberry Pi Compute Module 4/5 + 自作キャリア基板**
  - 基板に載せるもの: CM4/CM5 コネクタ(Hirose DF40 ×2)、5V 電源回路、USB-C 給電、状態 LED、ペアリングボタン、
    (第二候補用に)ライン入力 ADC(例: PCM1808 / ES8388)と 3.5mm ジャック。
  - Wi-Fi/Bluetooth はモジュール内蔵、外部アンテナ端子(U.FL)を使えば電波も安定。
  - 4 層基板推奨(高速信号は CM 側で完結しているので難度は中程度)。
- **案 B: ESP32 系 1 チップ**
  - ESP32(無印)は Classic Bluetooth の A2DP 受信ができるが、AirPlay **送信**の実装例がほぼなく、
    Bluetooth と Wi-Fi が同じ無線を時分割するため音切れリスクが高い。メモリも少ない。
  - 小型・低価格だが難度が高いので、現時点では非推奨。

## 9. 進め方

1. §7 の確認(プロジェクターが使える状態で、手持ちの Bluetooth 機器で数分)
2. Raspberry Pi 4/5 を用意 → OS 書き込み
3. BlueALSA で「Bluetooth スピーカー」として見えるようにする → プロジェクターとペアリング
4. libraop を Linux arm64 でビルド → BlueALSA の音を AirMac へ(まずはコマンドの組み合わせで)
5. 遅延申告を設定してリップシンク確認
6. 常駐プログラム化(自動接続・音量連動・自動起動)
7. 必要なら §8 の小型化

## 参考
- 端子: [Xiaomi L1 Pro 仕様(Euronics)](https://www.euronics.ee/en/tv/home-cinemas/home-cinema-projectors/bhr9588eu/xiaomi-l1-pro-smart-projector-black-projector), [manuals.plus](https://manuals.plus/asin/B0DZSXC4VB), [Xiaomi Japan L1 仕様](https://www.mi.com/jp/product/xiaomi-smart-projector-l1/specs/)
- 遅延申告: [org.bluealsa.PCM1(Delay / DelayAdjustment)](https://manpages.ubuntu.com/manpages/oracular/man7/org.bluealsa.PCM1.7.html), [BlueZ: A2DP transport の Delay を書き込み可能にするパッチ](https://patchwork.kernel.org/project/bluetooth/patch/20241026191434.556716-2-arkadiusz.bokowy@gmail.com/mbox)
- AirPlay 送信の代替: [PipeWire RAOP discover/sink](https://docs.pipewire.org/page_module_raop_discover.html)

## 10. 実装(2026-11-07、動作確認済み)

既存の Raspberry Pi 3 Model B+(Homebridge 用、有線 LAN、Debian 13)に同居させた。

| 部分 | 内容 |
|---|---|
| Bluetooth 受信 | **BlueALSA master(v5 系)をソースからビルド**(`--enable-aac`、fdk-aac)。Debian の 4.3.1 は AAC 非対応で、遅延の申告も BlueZ に伝えないため。`/etc/systemd/system/bluealsad.service`(`-p a2dp-sink -c SBC -c AAC --a2dp-force-audio-cd`) |
| 送信エンジン | `bridge/engine/airplay_bridge_engine.c`。ALSA で受信 → リングバッファ → libsamplerate の可変レート変換で時計のずれを吸収 → libraop。CPU 約 20%(cliraop は空回りで 93%) |
| 管理 Web | `bridge/controller/`(Python 標準ライブラリのみ)。`http://<Pi のホスト名>.local:8590/` |
| 自動起動 | `airplay-bridge.service`(systemd) |

確認できたこと:
- プロジェクターは **AAC 44.1kHz** で送ってくる。
- **遅延の申告が効く**: BlueALSA の ClientDelay(AirPlay 600 + バッファ 120 + 補正 30 = 750ms)が BlueZ の
  `MediaTransport1.Delay` 経由でプロジェクターに届き、映像が遅れて口の動きと音が合った。
- AirPlay 遅延 600ms で途切れなし(200ms だと Bluetooth の揺らぎで途切れた)。

つまずき点:
- Pi の Bluetooth が rfkill でソフトブロックされていた(Wi-Fi は有線運用なのでブロックのまま)。
- ユーザーセッションの WirePlumber が Bluetooth の音声を先に取っていた → `~/.config/wireplumber/wireplumber.conf.d/90-airplay-bridge-no-bluez.conf` で無効化。
- BlueALSA の SoftVolume が有効だと、プロジェクターの音量(0〜127)が音に掛かって小さくなる → オフにし、音量は AirPlay 側で扱う。
- プロジェクターの AirPlay Mirror アプリが動いていると、音がアプリの取得経路(REMOTE_SUBMIX)に流れ、Bluetooth に来ない →
  アプリの自動起動をオフにした。
- `pkill -f` は ssh のコマンド行自身にも一致して接続ごと殺すので、`pkill -x` を使う。
