package com.zyagasan.projectorairplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** 画面を閉じても動き続けるための前面サービス。状態は MirrorState に出す。 */
class MirrorService : Service() {
    private var session: MirrorSession? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            else -> if (intent != null) startSession(intent)
        }
        return START_NOT_STICKY
    }

    private fun startSession(i: Intent) {
        stopSession()
        val target = Target(
            i.getStringExtra("name")!!, i.getStringExtra("host")!!, i.getIntExtra("port", 7000), i.getStringExtra("et"),
        )
        val volume = i.getIntExtra("volume", 40)
        val latency = i.getIntExtra("latency", 4410)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AirPlay 送信", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("AirPlay に送信中")
            .setContentText(target.displayName)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        MirrorState.running = true
        MirrorState.add("開始: ${target.displayName} (${target.host}:${target.port})")
        session = MirrorSession(this, target, volume, latency, { MirrorState.add(it) }) {
            MirrorState.running = false
            MirrorState.notifyChanged()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.also { it.start() }
        MirrorState.notifyChanged()
    }

    private fun stopSession() {
        session?.stop()
        session = null
    }

    override fun onDestroy() { stopSession(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "mirror"
    }
}

/** Activity とサービスの間で共有する、画面表示用の状態。 */
object MirrorState {
    @Volatile var running = false
    private val lines = ArrayDeque<String>()
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized fun add(s: String) {
        lines.addLast(s)
        while (lines.size > 40) lines.removeFirst()
        notifyChanged()
    }
    @Synchronized fun text(): String = lines.joinToString("\n")
    fun notifyChanged() { listener?.invoke() }
}
