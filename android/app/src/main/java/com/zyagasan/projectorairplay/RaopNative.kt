package com.zyagasan.projectorairplay

/** libraop(AirPlay 1 / RAOP 送信)の JNI。PCM は 44.1kHz / 16bit / 2ch / リトルエンディアン。 */
object RaopNative {
    init { System.loadLibrary("raopjni") }

    /** 接続して送信ハンドルを返す。失敗時は 0。 */
    external fun open(host: String, port: Int, latencyFrames: Int, volume: Int, et: String?): Long

    /** PCM を渡す。実時間ペースで送るため、必要に応じてブロックする。0=継続 / -1=停止・失敗 */
    external fun send(handle: Long, data: ByteArray, off: Int, len: Int): Int
    external fun requestStop(handle: Long)
    external fun close(handle: Long)
    external fun latencyMs(handle: Long): Int
}
