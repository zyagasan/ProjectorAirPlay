package com.zyagasan.projectorairplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 電源オン(起動完了)時に、前回の送信先へ自動で送り始める。画面は開かず、前面サービスだけ起動する。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("boot", true) || prefs.getString("last", null) == null) return
        ctx.startForegroundService(Intent(ctx, MirrorService::class.java).setAction(MirrorService.ACTION_BOOT))
    }
}
