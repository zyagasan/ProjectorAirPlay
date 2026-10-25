package com.zyagasan.projectorairplay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * リモコン(D-pad)で操作する画面。送信先を選んで「開始」。
 * 起動時は前回の送信先が見つかり次第、自動で送信を開始する。
 */
class MainActivity : Activity() {
    private lateinit var discovery: RaopDiscovery
    private val main = Handler(Looper.getMainLooper())
    private val prefs get() = getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private var targets = listOf<Target>()
    private var selected: Target? = null
    private var volume = 40
    private var autoStart = true
    private var autoBoot = true
    private var pauseNetflix = true
    private var autoStartPending = false
    private var showLog = false

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var deviceBox: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var toggle: Button
    private lateinit var volumeText: TextView
    private lateinit var autoBtn: Button
    private lateinit var bootBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var logBtn: Button
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volume = prefs.getInt("volume", 40).coerceIn(0, MirrorSession.MAX_VOLUME)
        autoStart = prefs.getBoolean("auto", true)
        autoBoot = prefs.getBoolean("boot", true)
        pauseNetflix = prefs.getBoolean("pause_netflix", true)
        autoStartPending = autoStart && savedInstanceState == null && !MirrorState.running
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        buildUi()

        discovery = RaopDiscovery(this) { found ->
            targets = found
            if (selected == null || found.none { it.serviceName == selected?.serviceName }) {
                selected = found.firstOrNull { it.serviceName == (MirrorState.targetName ?: prefs.getString("last", null)) }
                    ?: selected?.takeIf { s -> found.any { it.serviceName == s.serviceName } }
            }
            renderDevices()
            maybeAutoStart()
        }
        if (autoStartPending) {
            MirrorState.setStatus(MirrorState.Status.CONNECTING, "前回の送信先を探しています…")
            main.postDelayed({
                if (autoStartPending) {
                    autoStartPending = false
                    val n = prefs.getString("last", null)?.substringAfter('@')
                    MirrorState.setStatus(MirrorState.Status.IDLE, if (n != null) "前回の送信先「$n」が見つかりません" else "送信先を選んでください")
                }
            }, 15000)
        }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
        MirrorState.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onStop() {
        MirrorState.listener = null
        discovery.stop()
        super.onStop()
    }

    // ---- 動作 ----

    private fun maybeAutoStart() {
        if (!autoStartPending || MirrorState.running) return
        val last = prefs.getString("last", null) ?: return
        val t = targets.firstOrNull { it.serviceName == last } ?: return
        autoStartPending = false
        selected = t
        startMirror(t)
    }

    private fun select(t: Target) {
        selected = t
        prefs.edit().putString("last", t.serviceName).apply()
        renderDevices()
        refresh()
    }

    private fun startMirror(t: Target) {
        prefs.edit().putString("last", t.serviceName).apply()
        val i = Intent(this, MirrorService::class.java)
            .putExtra("name", t.serviceName).putExtra("host", t.host).putExtra("port", t.port)
            .putExtra("et", t.et).putExtra("volume", volume)
        startForegroundService(i)
    }

    private fun onToggle() {
        autoStartPending = false
        if (MirrorState.running) {
            startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
        } else {
            selected?.let { startMirror(it) }
        }
    }

    private fun setVolume(v: Int) {
        volume = v.coerceIn(0, MirrorSession.MAX_VOLUME)
        prefs.edit().putInt("volume", volume).apply()
        refresh()
        if (MirrorState.running) {
            startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_VOLUME).putExtra("volume", volume))
        }
    }

    // ---- 画面 ----

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(56), dp(28), dp(56), dp(24))
        }

        // ヘッダ: タイトル + 状態
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("AirPlay Mirror", 32f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        statusDot = View(this)
        header.addView(statusDot, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(12) })
        statusText = label("", 24f, Color.WHITE)
        header.addView(statusText)
        root.addView(header)

        root.addView(label("送信先", 18f, MUTED).apply { setPadding(0, dp(24), 0, dp(8)) })
        deviceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        emptyHint = label("AirPlay 機器を探しています…", 22f, MUTED)
        val devScroll = ScrollView(this).apply { isFillViewport = false; addView(deviceBox) }
        root.addView(devScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // 操作列
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toggle = button("開始", 28f) { onToggle() }
        bar.addView(toggle, LinearLayout.LayoutParams(dp(260), dp(72)).apply { rightMargin = dp(32) })
        bar.addView(button("−", 30f) { setVolume(volume - 5) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        volumeText = label("", 24f, Color.WHITE).apply { gravity = Gravity.CENTER; minWidth = dp(220) }
        bar.addView(volumeText)
        bar.addView(button("＋", 30f) { setVolume(volume + 5) }, LinearLayout.LayoutParams(dp(72), dp(72)))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        // 設定列
        val opts = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        autoBtn = button("", 20f) {
            autoStart = !autoStart
            prefs.edit().putBoolean("auto", autoStart).apply()
            refresh()
        }
        opts.addView(autoBtn, LinearLayout.LayoutParams(dp(340), dp(56)).apply { rightMargin = dp(16) })
        bootBtn = button("", 20f) {
            autoBoot = !autoBoot
            prefs.edit().putBoolean("boot", autoBoot).apply()
            refresh()
        }
        opts.addView(bootBtn, LinearLayout.LayoutParams(dp(420), dp(56)).apply { rightMargin = dp(16) })
        logBtn = button("", 20f) { showLog = !showLog; refresh() }
        opts.addView(logBtn, LinearLayout.LayoutParams(dp(220), dp(56)))
        root.addView(opts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        val opts2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        pauseBtn = button("", 20f) {
            pauseNetflix = !pauseNetflix
            prefs.edit().putBoolean("pause_netflix", pauseNetflix).apply()
            refresh()
        }
        opts2.addView(pauseBtn, LinearLayout.LayoutParams(dp(560), dp(56)))
        root.addView(opts2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        logText = label("", 14f, Color.LTGRAY).apply { typeface = Typeface.MONOSPACE }
        logScroll = ScrollView(this).apply { addView(logText); visibility = View.GONE }
        root.addView(logScroll, LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(12) })

        setContentView(root)
        toggle.requestFocus()
    }

    private fun renderDevices() {
        val hadFocus = deviceBox.hasFocus()
        deviceBox.removeAllViews()
        if (targets.isEmpty()) { deviceBox.addView(emptyHint); return }
        targets.forEach { t ->
            val isSel = t.serviceName == selected?.serviceName
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), 0, dp(24), 0)
                background = focusBg(if (isSel) SELECTED else SURFACE, FOCUS)
                isFocusable = true
                isClickable = true
                setOnClickListener { select(t) }
            }
            row.addView(label(t.displayName, 26f, Color.WHITE, bold = isSel), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(t.host, 18f, MUTED).apply { setPadding(0, 0, dp(24), 0) })
            row.addView(label(if (isSel) "● 選択中" else "", 20f, Color.WHITE))
            deviceBox.addView(row, LinearLayout.LayoutParams(-1, dp(60)).apply { bottomMargin = dp(8) })
        }
        if (hadFocus) deviceBox.getChildAt(targets.indexOfFirst { it.serviceName == selected?.serviceName }.coerceAtLeast(0))?.requestFocus()
    }

    private fun refresh() {
        val st = MirrorState.status
        val color = when (st) {
            MirrorState.Status.STREAMING -> GREEN
            MirrorState.Status.CONNECTING -> AMBER
            MirrorState.Status.PAUSED -> BLUE
            MirrorState.Status.ERROR -> RED
            MirrorState.Status.IDLE -> MUTED
        }
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        statusText.text = MirrorState.detail

        toggle.text = if (MirrorState.running) "■  停止" else "▶  開始"
        toggle.isEnabled = MirrorState.running || selected != null
        toggle.alpha = if (toggle.isEnabled) 1f else 0.45f
        volumeText.text = "AirPlay 音量  $volume"
        autoBtn.text = "アプリ起動時に自動開始: " + if (autoStart) "オン" else "オフ"
        bootBtn.text = "電源オン時に自動起動: " + if (autoBoot) "オン" else "オフ"
        pauseBtn.text = "Netflix 視聴中は自動で一時停止: " + if (pauseNetflix) "オン" else "オフ"
        logBtn.text = if (showLog) "ログを隠す" else "ログを表示"
        logScroll.visibility = if (showLog) View.VISIBLE else View.GONE
        if (showLog) {
            logText.text = MirrorState.text()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ---- 部品 ----

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(text: String, sp: Float, onClick: () -> Unit) = Button(this).apply {
        this.text = text; textSize = sp; setTextColor(Color.WHITE); isAllCaps = false
        background = focusBg(SURFACE, FOCUS)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /** 通常は暗い面、フォーカス時は青 + 白枠で、離れた距離でも今どこを選んでいるか分かるようにする。 */
    private fun focusBg(normal: Int, focus: Int): StateListDrawable {
        fun shape(fill: Int, stroke: Int, w: Int) = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat(); setColor(fill); setStroke(w, stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(focus, Color.WHITE, dp(3)))
            addState(intArrayOf(android.R.attr.state_pressed), shape(focus, Color.WHITE, dp(3)))
            addState(intArrayOf(), shape(normal, Color.TRANSPARENT, 0))
        }
    }

    companion object {
        private val BG = Color.parseColor("#0E1217")
        private val SURFACE = Color.parseColor("#1F2630")
        private val SELECTED = Color.parseColor("#1D3B63")
        private val FOCUS = Color.parseColor("#2F6FEB")
        private val MUTED = Color.parseColor("#8B97A6")
        private val GREEN = Color.parseColor("#3DDC84")
        private val AMBER = Color.parseColor("#FFB020")
        private val RED = Color.parseColor("#FF5252")
        private val BLUE = Color.parseColor("#5AA9FF")
    }
}
