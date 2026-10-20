# AirPlay Mirror (Android TV / Google TV アプリ)

プロジェクター(FORMOVIE / Xiaomi L1 Pro, Android 14)の再生音を、AirPlay 1(RAOP)機器へ送る。
Mac を介さず、プロジェクター単体で動く。

```
adb(localhost:5555) ─▶ scrcpy-server(shell 権限で再生音を取得, PCM 48kHz)
   ─▶ リサンプル 44.1kHz ─▶ PcmQueue(溜まりすぎたら古い音を捨てる) ─▶ libraop(RAOP, JNI) ─▶ AirPlay 機器
```

## 前提
- 端末側で adb が TCP 5555 で待ち受けていること(`adb tcpip 5555`)。初回起動時にプロジェクターの画面に
  「USB デバッグを許可しますか?」が出るので「常に許可」で許可する(アプリが生成した RSA キーを登録する操作)。
- 送信先は mDNS(`_raop._tcp`)で自動検出。暗号化なし・パスワードなしの AirPlay 1 機器(AirMac Express で確認)。

## ビルド
```
scripts/build-libraop-mac.sh      # third_party/libraop を取得してパッチを当てる(Android 用にも必要)
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
ビルド環境: JDK 17 / Android SDK 34 / NDK 26.3 / CMake 3.22(Homebrew の android-commandlinetools)。

## 使い方
- 起動すると、前回の送信先が見つかり次第、自動で送信を開始する(「起動時に自動開始」で切り替え)。
- 状態は右上のランプで確認: 緑=送信中 / 黄=接続中 / 赤=失敗 / 灰=停止中。失敗時は最大 5 回まで自動で再接続する。
- 音量は送信中でも変更できる(再接続なし。上限 50)。
- 起動から再生開始まで約 4 秒(音声取得サーバー(jar)は端末に同じサイズのものがあれば転送しない。転送は約 9 秒かかる)。

## 電源オン時の自動起動
- 起動完了(`BOOT_COMPLETED`)を受けて、画面は開かずに前面サービスだけが立ち上がり、前回の送信先へ送り始める
  (画面の「電源オン時に自動起動」で切り替え)。
- 起動直後はネットワークがまだ使えないことがあるため、mDNS で前回の送信先を探し直し(20 秒)、見つからなければ保存済みの IP で試す。
  失敗しても最大 30 回まで再接続する。
- 前提: adb の TCP 5555 が再起動後も有効なこと(`persist.adb.tcp.port=5555` で確認済み)。
- 注意: アプリを一度も起動していない(または強制停止した)状態では Android が BOOT_COMPLETED を配信しない。インストール後は 1 回起動しておくこと。
- 注意: リモコンの電源ボタンが「スタンバイ」だけの場合は再起動にならないため、BOOT_COMPLETED は来ない
  (スタンバイからの復帰時はサービスが生きていればそのまま送信を続ける)。

## 設計メモ
- 音声取得は同梱の scrcpy-server(Apache-2.0, `app/src/main/assets/scrcpy-server.jar`, v5.0.1)を shell 権限で起動して使う。
  `audio_codec=raw`, `send_*_meta=false` で生の PCM をそのまま受け取る。
- **最初の音が届いてから AirPlay に接続する**(接続後に無音期間があると、最初の音が遅れて捨てられて無音になる)。
- RAOP 遅延は 200ms(`RAOP_LATENCY_MIN=4410` + `-l 4410`)。AirMac Express で途切れなく再生できた。
- RAOP 音量の上限は 50(100 は大きすぎる)。
- libraop の OpenSSL 依存は `ssl_stubs.c` で代替(乱数だけ実装。AppleTV ペアリング・パスワード・RSA 暗号化は未対応)。
