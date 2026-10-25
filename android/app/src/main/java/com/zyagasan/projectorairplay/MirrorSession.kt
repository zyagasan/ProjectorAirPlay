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
    private val onStatus: (MirrorState.Status, String) -> Unit,
    private val onEnded: (failed: Boolean) -> Unit,
) {
    @Volatile private var stopped = false
    @Volatile private var failed = false
    @Volatile private var raop = 0L
    private var dadb: Dadb? = null
    private var scid: String? = null
    private var worker: Thread? = null
    private val queue = PcmQueue(maxBytes = 44100 * 4 * 300 / 1000, targetBytes = 44100 * 4 * 60 / 1000)

    fun start() {
        worker = thread(name = "mirror-main") {
            try { run() } catch (e: Throwable) { if (!stopped) fail("エラー: ${e.message ?: e}") }
            finally { val f = failed && !userStopped; cleanup(); onEnded(f) }
        }
    }

    @Volatile private var userStopped = false

    /** 送信中でも音量だけ変える(再接続は不要)。 */
    fun setVolume(v: Int) { val h = raop; if (h != 0L) RaopNative.setVolume(h, v.coerceIn(0, MAX_VOLUME)) }

    private fun fail(msg: String) {
        failed = true
        log(msg)
        onStatus(MirrorState.Status.ERROR, msg)
    }

    fun stop() {
        userStopped = true
        stopped = true
        if (raop != 0L) RaopNative.requestStop(raop)
        queue.close()
        runCatching { dadb?.close() }
    }

    private val t0 = System.currentTimeMillis()
    private fun el() = "+${System.currentTimeMillis() - t0}ms"

    private fun run() {
        onStatus(MirrorState.Status.CONNECTING, "プロジェクターに接続中…")
        val db = connectAdb() ?: return
        dadb = db
        // 前面アプリの検出(Netflix 視聴中の一時停止)に使う権限を、adb(shell 権限)で付与しておく
        runCatching { db.shell("appops set ${ctx.packageName} GET_USAGE_STATS allow") }
        onStatus(MirrorState.Status.CONNECTING, "音声取得を準備中…")

        // 取得サーバー(scrcpy-server)を端末へ置いて、shell 権限で起動する
        val jar = File(ctx.cacheDir, "scrcpy-server.jar")
        ctx.assets.open("scrcpy-server.jar").use { i -> jar.outputStream().use { o -> i.copyTo(o) } }
        val remoteJar = "/data/local/tmp/scrcpy-server.jar"
        // push は遅い(約9秒)ので、端末に同じサイズのファイルがあれば省く
        val remoteSize = runCatching { db.shell("stat -c %s $remoteJar").output.trim().toLong() }.getOrDefault(-1L)
        if (remoteSize != jar.length()) db.push(jar, remoteJar)
        log("サーバー転送完了 ${el()}")
        db.shell("pkill -f com.genymobile.scrcpy.Server")

        val scid = "%08x".format((System.nanoTime() and 0x7fffffff).toInt()).also { this.scid = it }
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

        log("音声取得サーバー起動 ${el()}")
        var stream: dadb.AdbStream? = null
        for (i in 0 until 40) {
            if (stopped) return
            try { stream = db.open("localabstract:scrcpy_$scid"); break } catch (e: Exception) { Thread.sleep(250) }
        }
        if (stream == null) { fail("音声取得サーバーに接続できませんでした"); return }
        val src = stream.source
        log("音声ストリーム接続 ${el()}")

        // 最初の音が届いてから AirPlay に接続する(接続後に無音期間があると、最初の音が遅れて捨てられる)
        val resampler = Resampler()
        val buf = ByteArray(8192)
        var n = src.read(buf, 0, buf.size)
        if (n <= 0) { fail("音声が取得できませんでした"); return }
        queue.put(resampler.process(buf, n))
        log("最初の音を受信 ${el()}")

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
        onStatus(MirrorState.Status.CONNECTING, "${target.displayName} に接続中…")
        raop = RaopNative.open(target.host, target.port, latencyFrames, volume.coerceAtMost(MAX_VOLUME), target.et)
        if (raop == 0L) { fail("${target.displayName} に接続できませんでした"); return }
        log("再生開始 ${el()}(遅延 ${RaopNative.latencyMs(raop)}ms / 音量 $volume)")
        onStatus(MirrorState.Status.STREAMING, "${target.displayName} へ送信中")

        while (!stopped) {
            val chunk = queue.take(4096) ?: break
            if (RaopNative.send(raop, chunk, 0, chunk.size) != 0) break
        }
        if (!stopped) fail("送信が途切れました")
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
                if (r.output.trim() == "ok") { log("adb 接続 OK ${el()}"); return db }
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
        // 自分のセッションのサーバーだけ止める(後から始まった別セッションのサーバーを巻き込まない)
        scid?.let { id -> runCatching { dadb?.shell("pkill -f scid=$id") } }
        runCatching { dadb?.close() }
    }

    companion object {
        const val SERVER_VERSION = "5.0.1"
        const val MAX_VOLUME = 50      // これ以上は大きすぎる(100 は禁止)
    }
}
