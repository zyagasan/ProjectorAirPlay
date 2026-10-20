# ProjectorAirPlay

Google TV 内蔵プロジェクター(FORMOVIE / Xiaomi L1 Pro, C045RGN, Android 14)の音声出力を、
AirPlay 1(RAOP)機器(AirMac Express「メインスピーカー」)へ飛ばすアプリ。

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

## 次のステップ
1. Mac から AirMac Express へ PCM を RAOP 送信するテスト(libraop)
2. キャプチャ用プロセス(Java, app_process)
3. Android アプリ(Kotlin, mDNS 検索, Leanback UI, adb 接続)

## 検証コマンド
```
export ADB="/Applications/platform-tools 2/adb"
scrcpy --no-video --no-playback --audio-source=output --audio-codec=raw --time-limit=10 --record=cap.wav
ffmpeg -i cap.wav -af volumedetect -f null -
```
