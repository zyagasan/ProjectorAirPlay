package com.zyagasan.projectorairplay

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * いま前面にあるアプリを UsageStats から調べる(adb 経由の dumpsys より軽い)。
 * 権限(GET_USAGE_STATS)は、アプリが adb 接続した時に `appops set` で付与する。
 */
class ForegroundWatcher(private val ctx: Context) {
    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var fg: String? = null
    private var lastQuery = 0L

    fun hasPermission(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** 前面のアプリのパッケージ名。分からなければ null。 */
    fun current(): String? {
        if (!hasPermission()) return null
        val now = System.currentTimeMillis()
        // 初回は少し遡って現在の状態を拾い、以降は前回以降のイベントだけを見る
        val from = if (lastQuery == 0L) now - 10 * 60_000 else lastQuery - 1_000
        lastQuery = now
        val ev = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> fg = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (e.packageName == fg) fg = null
            }
        }
        return fg
    }
}
