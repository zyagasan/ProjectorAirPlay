package com.zyagasan.projectorairplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioManager
import android.widget.Toast

/** 画面を閉じても動き続けるための前面サービス。状態は MirrorState に出す。 */
class MirrorService : Service() {
    private var toast: Toast? = null
    private var volumeReceiverRegistered = false
    private var originalSystemVolume = -1
    private var baseSystemVolume = 1       // 端末の音量を常にこの値に保ち、ボタンで動いた分だけを AirPlay 音量に反映する
    private var appliedDelta = 0           // いまの「ボタンで動かした分」のうち AirPlay 音量へ反映済みの段数

    /**
     * リモコンの音量ボタンは、この端末ではメーカー製の部品が先に処理するため、通常のキー入力としては取れない。
     * 代わりに「端末の音量が変わった」通知を受け取り、基準からの差分を AirPlay 音量の増減に変換して、端末の音量は基準へ戻す。
     */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            val nv = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            if (nv < 0) return
            if (nv == baseSystemVolume) { appliedDelta = 0; return }               // 基準へ戻った(自分で戻した分を含む)
            if (!getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("volkeys", true)) return
            if (!MirrorState.running || MirrorState.status != MirrorState.Status.STREAMING) return
            val delta = nv - baseSystemVolume
            val change = delta - appliedDelta                                      // 続けて押された分だけを反映する
            appliedDelta = delta
            if (change != 0) {
                val v = changeVolume(c, MirrorState.volume + change * VOLUME_STEP)
                toast?.cancel()
                toast = Toast.makeText(c, "AirPlay 音量  $v", Toast.LENGTH_SHORT).also { it.show() }
            }
            setSystemVolume(baseSystemVolume)
        }
    }

    private fun setSystemVolume(v: Int) {
        runCatching { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
    }

    private fun startVolumeKeys() {
        if (volumeReceiverRegistered) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalSystemVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        // 下げる方向も検出できるよう、基準は 1 以上・最大未満にする(0 のときは 1=ほぼ無音にして、終了時に元へ戻す)
        baseSystemVolume = originalSystemVolume.coerceIn(1, (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) - 1).coerceAtLeast(1))
        appliedDelta = 0
        if (baseSystemVolume != originalSystemVolume) setSystemVolume(baseSystemVolume)
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_EXPORTED)
        volumeReceiverRegistered = true
    }

    /** 一時停止中(Netflix など)は端末の音量をそのまま使えるよう、受信だけ止める(音量は元に戻さない)。 */
    private fun suspendVolumeKeys() {
        if (!volumeReceiverRegistered) return
        runCatching { unregisterReceiver(volumeReceiver) }
        volumeReceiverRegistered = false
        originalSystemVolume = -1
    }

    private fun stopVolumeKeys() {
        if (!volumeReceiverRegistered) return
        runCatching { unregisterReceiver(volumeReceiver) }
        volumeReceiverRegistered = false
        if (originalSystemVolume >= 0) setSystemVolume(originalSystemVolume)
        originalSystemVolume = -1
    }

    private var watcher: ForegroundWatcher? = null
    private var paused = false
    private var pausedFor: String? = null
    private val watchRunnable = object : Runnable {
        override fun run() {
            checkForeground()
            main.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }
    private var discovery: RaopDiscovery? = null
    private var maxRetries = MAX_RETRIES
    private var session: MirrorSession? = null
    private var request: Intent? = null
    private var retries = 0
    private val main = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { request?.let { startSession(it, resetRetries = false) } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAll()
            ACTION_BOOT -> bootStart()
            ACTION_VOLUME -> session?.setVolume(intent.getIntExtra("volume", 40))
            else -> if (intent != null) startSession(intent, resetRetries = true)
        }
        return START_NOT_STICKY
    }

    private fun startSession(i: Intent, resetRetries: Boolean) {
        main.removeCallbacks(retryRunnable)
        session?.stop()
        if (resetRetries) retries = 0
        request = i
        val target = Target(
            i.getStringExtra("name")!!, i.getStringExtra("host")!!, i.getIntExtra("port", 7000), i.getStringExtra("et"),
        )
        val volume = i.getIntExtra("volume", 40)
        MirrorState.volume = volume

        showForeground("AirPlay に送信中", target.displayName)
        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putString("last", target.serviceName).putString("last_host", target.host)
            .putInt("last_port", target.port).putString("last_et", target.et).apply()

        MirrorState.running = true
        MirrorState.targetName = target.serviceName
        MirrorState.setStatus(MirrorState.Status.CONNECTING, "接続中…")
        MirrorState.add("開始: ${target.displayName} (${target.host}:${target.port})")
        session = MirrorSession(this, target, volume, LATENCY_FRAMES,
            log = { MirrorState.add(it) },
            onStatus = { st, msg -> MirrorState.setStatus(st, msg) },
        ) { failed ->
            session = null
            main.post {
                if (paused) return@post
                if (failed && retries < maxRetries && request != null) {
                    retries++
                    MirrorState.setStatus(MirrorState.Status.CONNECTING, "再接続します(${retries}/$maxRetries)…")
                    main.postDelayed(retryRunnable, 4000)
                } else {
                    finish()
                }
            }
        }.also { it.start() }
        startWatching()
        startVolumeKeys()
    }

    /** Netflix などの前面表示中は、音声取得が動いているだけで再生できなくなるため、取得を止める。 */
    private fun startWatching() {
        if (watcher == null) watcher = ForegroundWatcher(this)
        main.removeCallbacks(watchRunnable)
        main.postDelayed(watchRunnable, WATCH_INTERVAL_MS)
    }

    private fun checkForeground() {
        if (!getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("pause_netflix", true)) {
            if (paused) resumeAfterPause()
            return
        }
        val fg = watcher?.current()
        val blocked = fg != null && fg in PAUSE_PACKAGES
        if (!paused && blocked) {
            paused = true
            pausedFor = fg
            main.removeCallbacks(retryRunnable)
            session?.stop()
            session = null
            suspendVolumeKeys()
            MirrorState.add("前面が $fg のため、音声取得を一時停止")
            MirrorState.setStatus(MirrorState.Status.PAUSED, "Netflix 視聴中は一時停止")
        } else if (paused && !blocked) {
            resumeAfterPause()
        }
    }

    private fun resumeAfterPause() {
        paused = false
        pausedFor = null
        MirrorState.add("音声取得を再開")
        request?.let { startSession(it, resetRetries = true) }
    }

    private fun showForeground(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AirPlay 送信", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    /**
     * 電源オン時の開始。ネットワークがまだ使えないことがあるので、mDNS で前回の送信先を探し直し、
     * 見つからなければ保存してある IP で試す。接続に失敗しても長めに再試行する。
     */
    private fun bootStart() {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("last", null) ?: run { stopSelf(); return }
        showForeground("AirPlay Mirror", "前回の送信先を探しています…")
        MirrorState.running = true
        MirrorState.targetName = name
        MirrorState.setStatus(MirrorState.Status.CONNECTING, "起動中: 前回の送信先を探しています…")
        maxRetries = BOOT_RETRIES
        val volume = prefs.getInt("volume", 40)

        var launched = false
        fun launch(t: Target) {
            if (launched) return
            launched = true
            discovery?.stop(); discovery = null
            main.removeCallbacks(bootFallback)
            startSession(
                Intent().putExtra("name", t.serviceName).putExtra("host", t.host)
                    .putExtra("port", t.port).putExtra("et", t.et).putExtra("volume", volume),
                resetRetries = true,
            )
        }
        bootFallback = Runnable {
            discovery?.stop(); discovery = null
            val host = prefs.getString("last_host", null)
            if (host != null) launch(Target(name, host, prefs.getInt("last_port", 7000), prefs.getString("last_et", null)))
            else finish()
        }
        discovery = RaopDiscovery(this) { found -> found.firstOrNull { it.serviceName == name }?.let { launch(it) } }
            .also { it.start() }
        main.postDelayed(bootFallback, 20000)
    }

    private var bootFallback = Runnable {}

    private fun stopAll() {
        main.removeCallbacks(watchRunnable)
        paused = false
        discovery?.stop(); discovery = null
        main.removeCallbacks(bootFallback)
        main.removeCallbacks(retryRunnable)
        request = null
        session?.stop()
        session = null
        finish()
    }

    private fun finish() {
        stopVolumeKeys()
        main.removeCallbacks(watchRunnable)
        val st = MirrorState.status
        MirrorState.running = false
        if (st != MirrorState.Status.ERROR) MirrorState.setStatus(MirrorState.Status.IDLE, "停止中")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopVolumeKeys(); main.removeCallbacks(watchRunnable); discovery?.stop(); main.removeCallbacks(bootFallback); main.removeCallbacks(retryRunnable); session?.stop(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_VOLUME = "volume"
        const val ACTION_BOOT = "boot"
        const val VOLUME_STEP = 5

        /** AirPlay 音量(0〜100)を変更する。保存し、送信中ならすぐ反映する。返り値は設定後の値。 */
        fun changeVolume(ctx: Context, v: Int): Int {
            val nv = v.coerceIn(0, MirrorSession.MAX_VOLUME)
            MirrorState.volume = nv
            ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putInt("volume", nv).apply()
            if (MirrorState.running) {
                ctx.startService(Intent(ctx, MirrorService::class.java).setAction(ACTION_VOLUME).putExtra("volume", nv))
            }
            MirrorState.notifyChanged()
            return nv
        }
        private const val BOOT_RETRIES = 30
        private const val WATCH_INTERVAL_MS = 700L
        /** 音声取得が動いていると再生できなくなるアプリ(この端末の Netflix)。 */
        private val PAUSE_PACKAGES = setOf("com.netflix.ninja")
        const val LATENCY_FRAMES = 4410      // RAOP 遅延 約200ms
        private const val MAX_RETRIES = 5
        private const val CHANNEL = "mirror"
    }
}

/** Activity とサービスの間で共有する、画面表示用の状態。 */
object MirrorState {
    enum class Status { IDLE, CONNECTING, STREAMING, PAUSED, ERROR }

    @Volatile var running = false
    @Volatile var status = Status.IDLE
    @Volatile var detail = "停止中"
    @Volatile var targetName: String? = null
    @Volatile var volume = 40
    private val lines = ArrayDeque<String>()
    @Volatile var listener: (() -> Unit)? = null

    fun setStatus(s: Status, d: String) { status = s; detail = d; notifyChanged() }

    @Synchronized fun add(s: String) {
        android.util.Log.i("MirrorState", s)
        lines.addLast(s)
        while (lines.size > 40) lines.removeFirst()
        notifyChanged()
    }
    @Synchronized fun text(): String = lines.joinToString("\n")
    fun notifyChanged() { listener?.invoke() }
}
