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

/** 画面を閉じても動き続けるための前面サービス。状態は MirrorState に出す。 */
class MirrorService : Service() {
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
                if (failed && retries < maxRetries && request != null) {
                    retries++
                    MirrorState.setStatus(MirrorState.Status.CONNECTING, "再接続します(${retries}/$maxRetries)…")
                    main.postDelayed(retryRunnable, 4000)
                } else {
                    finish()
                }
            }
        }.also { it.start() }
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
        discovery?.stop(); discovery = null
        main.removeCallbacks(bootFallback)
        main.removeCallbacks(retryRunnable)
        request = null
        session?.stop()
        session = null
        finish()
    }

    private fun finish() {
        val st = MirrorState.status
        MirrorState.running = false
        if (st != MirrorState.Status.ERROR) MirrorState.setStatus(MirrorState.Status.IDLE, "停止中")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { discovery?.stop(); main.removeCallbacks(bootFallback); main.removeCallbacks(retryRunnable); session?.stop(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_VOLUME = "volume"
        const val ACTION_BOOT = "boot"
        private const val BOOT_RETRIES = 30
        const val LATENCY_FRAMES = 4410      // RAOP 遅延 約200ms
        private const val MAX_RETRIES = 5
        private const val CHANNEL = "mirror"
    }
}

/** Activity とサービスの間で共有する、画面表示用の状態。 */
object MirrorState {
    enum class Status { IDLE, CONNECTING, STREAMING, ERROR }

    @Volatile var running = false
    @Volatile var status = Status.IDLE
    @Volatile var detail = "停止中"
    @Volatile var targetName: String? = null
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
