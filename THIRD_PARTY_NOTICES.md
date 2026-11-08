# 第三者のソフトウェア

| ソフトウェア | 用途 | ライセンス | リポジトリでの扱い |
|---|---|---|---|
| [libraop](https://github.com/philippe44/libraop) | AirPlay 1(RAOP)の送信 | MIT(Philippe) | ビルド時に取得し、`patches/` の変更を適用 |
| [scrcpy](https://github.com/Genymobile/scrcpy) サーバー v5.0.1 | Android アプリの音声取得 | Apache-2.0(Genymobile) | `android/app/src/main/assets/scrcpy-server.jar` を同梱 |
| [Gradle Wrapper](https://gradle.org/) | Android アプリのビルド | Apache-2.0 | `android/gradle/wrapper/` を同梱 |
| [BlueALSA](https://github.com/arkq/bluez-alsa) | Bluetooth 音声の受信 | MIT | 利用者がビルド(同梱なし) |
| [libsamplerate](https://github.com/libsndfile/libsamplerate) | 可変レート変換 | BSD-2-Clause | OS のパッケージを利用(同梱なし) |

同梱している `scrcpy-server.jar` の SHA-256 は `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536` で、
公式リリース v5.0.1 の値と一致することを確認しました。

AirPlay と AirMac は Apple Inc. の商標です。このプロジェクトは Apple、Netflix、Google、Xiaomi とは関係ありません。
