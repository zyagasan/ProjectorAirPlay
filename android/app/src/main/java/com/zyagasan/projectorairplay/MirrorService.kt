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
    private var session: MirrorSession? = null
    private var request: Intent? = null
    private var retries = 0
    private val main = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { request?.let { startSession(it, resetRetries = false) } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAll()
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

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AirPlay 送信", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("AirPlay に送信中")
            .setContentText(target.displayName)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

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
                if (failed && retries < MAX_RETRIES && request != null) {
                    retries++
                    MirrorState.setStatus(MirrorState.Status.CONNECTING, "再接続します(${retries}/$MAX_RETRIES)…")
                    main.postDelayed(retryRunnable, 4000)
                } else {
                    finish()
                }
            }
        }.also { it.start() }
    }

    private fun stopAll() {
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

    override fun onDestroy() { main.removeCallbacks(retryRunnable); session?.stop(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_VOLUME = "volume"
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
