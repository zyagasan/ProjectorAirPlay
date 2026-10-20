package com.zyagasan.projectorairplay

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import dadb.AdbShellResponse
import java.io.File
import kotlin.concurrent.thread

/**
 * プロジェクターの再生音を AirPlay 機器へ送る 1 回分のセッション。
 *
 *  [adb(localhost:5555)] -> scrcpy-server(shell 権限で再生音を取得) -> PCM 48k
 *       -> リサンプル 44.1k -> PcmQueue -> libraop(RAOP) -> AirPlay 機器
 */
class MirrorSession(
    private val ctx: Context,
    private val target: Target,
    private val volume: Int,
    private val latencyFrames: Int,
    private val log: (String) -> Unit,
    private val onEnded: () -> Unit,
) {
    @Volatile private var stopped = false
    @Volatile private var raop = 0L
    private var dadb: Dadb? = null
    private var worker: Thread? = null
    private val queue = PcmQueue(maxBytes = 44100 * 4 * 300 / 1000, targetBytes = 44100 * 4 * 60 / 1000)

    fun start() {
        worker = thread(name = "mirror-main") {
            try { run() } catch (e: Throwable) { if (!stopped) log("エラー: ${e.message ?: e}") }
            finally { cleanup(); onEnded() }
        }
    }

    fun stop() {
        stopped = true
        if (raop != 0L) RaopNative.requestStop(raop)
        queue.close()
        runCatching { dadb?.close() }
    }

    private fun run() {
        val db = connectAdb() ?: return
        dadb = db

        // 取得サーバー(scrcpy-server)を端末へ置いて、shell 権限で起動する
        val jar = File(ctx.cacheDir, "scrcpy-server.jar")
        ctx.assets.open("scrcpy-server.jar").use { i -> jar.outputStream().use { o -> i.copyTo(o) } }
        val remoteJar = "/data/local/tmp/scrcpy-server.jar"
        db.push(jar, remoteJar)
        db.shell("pkill -f com.genymobile.scrcpy.Server")

        val scid = "%08x".format((System.nanoTime() and 0x7fffffff).toInt())
        val cmd = "CLASSPATH=$remoteJar app_process / com.genymobile.scrcpy.Server $SERVER_VERSION " +
            "scid=$scid log_level=info video=false audio=true control=false audio_codec=raw " +
            "audio_source=playback tunnel_forward=true send_device_meta=false send_dummy_byte=false " +
            "send_frame_meta=false send_stream_meta=false cleanup=false"
        thread(name = "server-log") {
            runCatching {
                val sh = db.openShell(cmd)
                while (!stopped) {
                    val p = sh.read()
                    if (p is dadb.AdbShellPacket.Exit) break
                    val text = String(p.payload).trim()
                    if (text.isNotEmpty()) log("server: $text")
                }
            }
        }

        log("音声取得サーバーに接続中…")
        var stream: dadb.AdbStream? = null
        for (i in 0 until 40) {
            if (stopped) return
            try { stream = db.open("localabstract:scrcpy_$scid"); break } catch (e: Exception) { Thread.sleep(250) }
        }
        if (stream == null) { log("音声取得サーバーに接続できませんでした"); return }
        val src = stream.source

        // 最初の音が届いてから AirPlay に接続する(接続後に無音期間があると、最初の音が遅れて捨てられる)
        val resampler = Resampler()
        val buf = ByteArray(8192)
        var n = src.read(buf, 0, buf.size)
        if (n <= 0) { log("音声が取得できませんでした"); return }
        queue.put(resampler.process(buf, n))

        thread(name = "capture-read") {
            runCatching {
                while (!stopped) {
                    val r = src.read(buf, 0, buf.size)
                    if (r <= 0) break
                    queue.put(resampler.process(buf, r))
                }
            }
            queue.close()
        }

        log("${target.displayName} に接続中…")
        raop = RaopNative.open(target.host, target.port, latencyFrames, volume.coerceAtMost(MAX_VOLUME), target.et)
        if (raop == 0L) { log("AirPlay 機器に接続できませんでした"); return }
        log("再生開始(遅延 ${RaopNative.latencyMs(raop)}ms / 音量 $volume)")

        while (!stopped) {
            val chunk = queue.take(4096) ?: break
            if (RaopNative.send(raop, chunk, 0, chunk.size) != 0) break
        }
        log("停止しました(破棄した音: ${queue.droppedBytes / 4 * 1000 / 44100}ms)")
    }

    /** 端末自身の adb(TCP 5555)へ接続する。初回はプロジェクターの画面で許可が必要。 */
    private fun connectAdb(): Dadb? {
        val dir = ctx.filesDir
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists()) AdbKeyPair.generate(priv, pub)
        val key = AdbKeyPair.read(priv, pub)
        var tries = 0
        while (!stopped) {
            try {
                val db = Dadb.create("127.0.0.1", 5555, key)
                val r: AdbShellResponse = db.shell("echo ok")
                if (r.output.trim() == "ok") { log("adb 接続 OK"); return db }
            } catch (e: Exception) {
                if (tries++ % 5 == 0) log("adb 接続待ち… プロジェクターの画面に許可ダイアログが出たら「常に許可」を選んでください(${e.message})")
                Thread.sleep(2000)
            }
        }
        return null
    }

    private fun cleanup() {
        stopped = true
        queue.close()
        val h = raop
        raop = 0
        if (h != 0L) RaopNative.close(h)
        runCatching { dadb?.shell("pkill -f com.genymobile.scrcpy.Server") }
        runCatching { dadb?.close() }
    }

    companion object {
        const val SERVER_VERSION = "5.0.1"
        const val MAX_VOLUME = 50      // これ以上は大きすぎる(100 は禁止)
    }
}
