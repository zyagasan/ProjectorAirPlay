package com.zyagasan.projectorairplay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * リモコン(D-pad)で操作する画面。
 *  左: いまの状態(大きな表示)と 開始/停止
 *  右: 送信先カード / 音量バー / 設定スイッチ
 * 起動時は前回の送信先が見つかり次第、自動で送信を開始する。
 */
class MainActivity : Activity() {
    private lateinit var discovery: RaopDiscovery
    private val main = Handler(Looper.getMainLooper())
    private val prefs get() = getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private var targets = listOf<Target>()
    private var selected: Target? = null
    private var autoStartPending = false

    private lateinit var orb: StatusOrb
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var startBtn: TextView
    private lateinit var deviceBox: LinearLayout
    private lateinit var deviceCount: TextView
    private lateinit var volumeValue: TextView
    private lateinit var volumeBar: VolumeBar
    private lateinit var tAuto: ToggleRow
    private lateinit var tBoot: ToggleRow
    private lateinit var tNetflix: ToggleRow
    private lateinit var tKeys: ToggleRow
    private lateinit var logToggle: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var showLog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MirrorState.volume = prefs.getInt("volume", 40).coerceIn(0, MirrorSession.MAX_VOLUME)
        autoStartPending = prefs.getBoolean("auto", true) && savedInstanceState == null && !MirrorState.running
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
            refresh()
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
            .putExtra("et", t.et).putExtra("volume", MirrorState.volume)
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

    private fun setPref(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    // ---- 画面の組み立て ----

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.BG_TOP, Palette.BG_BOTTOM))
            setPadding(dp(56), dp(36), dp(56), dp(32))
            clipChildren = false; clipToPadding = false
        }

        // ---- 左: 状態 ----
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        left.addView(text("AirPlay Mirror", 22f, Palette.MUTED, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        orb = StatusOrb(this)
        left.addView(orb, LinearLayout.LayoutParams(dp(300), dp(300)).apply { topMargin = dp(12) })
        statusText = text("", 28f, Palette.TEXT, bold = true).apply { gravity = Gravity.CENTER; maxLines = 2 }
        left.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        subText = text("", 17f, Palette.MUTED).apply { gravity = Gravity.CENTER; maxLines = 2 }
        left.addView(subText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        left.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        startBtn = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 26f; typeface = Typeface.DEFAULT_BOLD
            isFocusable = true; isClickable = true
            setOnClickListener { onToggle() }
            scaleOnFocus(1.05f)
        }
        left.addView(startBtn, LinearLayout.LayoutParams(-1, dp(84)))
        root.addView(left, LinearLayout.LayoutParams(dp(360), -1))

        // ---- 右: 送信先 / 音量 / 設定 ----
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("送信先", 22f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        deviceCount = text("", 15f, Palette.MUTED)
        head.addView(deviceCount)
        right.addView(head)

        deviceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; setPadding(dp(6), dp(8), dp(6), dp(8)) }
        val devScroll = ScrollView(this).apply { addView(deviceBox); clipChildren = false; clipToPadding = false; isVerticalScrollBarEnabled = false }
        right.addView(devScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // 音量
        val volHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        volHead.addView(text("AirPlay 音量", 20f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        volumeValue = text("", 30f, Palette.TEXT, bold = true)
        volHead.addView(volumeValue)
        right.addView(volHead, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        volumeBar = VolumeBar(this).apply { onUserChange = { MirrorService.changeVolume(this@MainActivity, it); refresh() } }
        right.addView(volumeBar, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(2) })
        right.addView(text("バーを選んで ← → で調整。リモコンの音量ボタン(＋/−)でも変えられます", 14f, Palette.MUTED),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        // 設定
        right.addView(text("設定", 18f, Palette.MUTED, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        tAuto = ToggleRow(this, "アプリを開いたら自動で開始") { setPref("auto", it) }
        tBoot = ToggleRow(this, "電源オンで自動起動") { setPref("boot", it) }
        tNetflix = ToggleRow(this, "Netflix 視聴中は一時停止") { setPref("pause_netflix", it) }
        tKeys = ToggleRow(this, "音量ボタンで AirPlay 音量") { setPref("volkeys", it) }
        val grid = LinearLayout(this).apply { clipChildren = false }
        fun col(a: View, b: View) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false
            addView(a, LinearLayout.LayoutParams(-1, dp(54))); addView(b, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) }) }
        grid.addView(col(tAuto, tBoot), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        grid.addView(col(tNetflix, tKeys), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        right.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        logToggle = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 15f; setTextColor(Palette.MUTED); typeface = medium()
            background = focusableBg(cardBg(Color.TRANSPARENT, radiusDp = 12f), radiusDp = 12f)
            isFocusable = true; isClickable = true
            setOnClickListener { showLog = !showLog; refresh() }
            scaleOnFocus(1.04f)
        }
        right.addView(logToggle, LinearLayout.LayoutParams(dp(180), dp(40)).apply { topMargin = dp(6); gravity = Gravity.END })
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(56) })

        // ログは全面に重ねて表示(普段は非表示)
        logText = text("", 14f, Palette.TEXT).apply { typeface = Typeface.MONOSPACE; setPadding(dp(24), dp(20), dp(24), dp(20)) }
        logScroll = ScrollView(this).apply {
            addView(logText); visibility = View.GONE
            background = cardBg(Color.argb(235, 9, 13, 18), Palette.SURFACE_HI, 2f, 20f)
        }
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        frame.addView(logScroll, FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM).apply { setMargins(dp(56), 0, dp(56), dp(110)) })
        setContentView(frame)
        startBtn.requestFocus()
    }

    private fun renderDevices() {
        val focusedIdx = (0 until deviceBox.childCount).firstOrNull { deviceBox.getChildAt(it).hasFocus() }
        deviceBox.removeAllViews()
        deviceCount.text = if (targets.isEmpty()) "探しています…" else "${targets.size} 台"
        if (targets.isEmpty()) {
            deviceBox.addView(text("AirPlay 機器を探しています…", 20f, Palette.MUTED).apply { setPadding(dp(8), dp(16), 0, 0) })
            return
        }
        targets.forEachIndexed { idx, t ->
            val isSel = t.serviceName == selected?.serviceName
            val card = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(22), 0, dp(22), 0)
                background = focusableBg(
                    if (isSel) cardBg(Palette.SELECTED, Palette.ACCENT, 2f) else cardBg(Palette.SURFACE),
                    focusFill = if (isSel) Palette.SELECTED else Palette.SURFACE_HI,
                )
                isFocusable = true; isClickable = true
                setOnClickListener { select(t) }
                scaleOnFocus(1.03f)
            }
            card.addView(text(if (isSel) "◉" else "○", 22f, if (isSel) Palette.ACCENT2 else Palette.MUTED).apply { setPadding(0, 0, dp(16), 0) })
            card.addView(text(t.displayName, 23f, Palette.TEXT, bold = isSel), LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(text(t.host, 16f, Palette.MUTED).apply { setPadding(0, 0, dp(14), 0) })
            if (isSel) card.addView(text("送信先", 15f, Palette.ACCENT2, bold = true))
            deviceBox.addView(card, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(10) })
        }
        focusedIdx?.let { deviceBox.getChildAt(it.coerceAtMost(deviceBox.childCount - 1))?.requestFocus() }
    }

    private fun refresh() {
        val st = MirrorState.status
        orb.state = when (st) {
            MirrorState.Status.IDLE -> StatusOrb.State.IDLE
            MirrorState.Status.CONNECTING -> StatusOrb.State.CONNECTING
            MirrorState.Status.STREAMING -> StatusOrb.State.STREAMING
            MirrorState.Status.PAUSED -> StatusOrb.State.PAUSED
            MirrorState.Status.ERROR -> StatusOrb.State.ERROR
        }
        statusText.text = MirrorState.detail.ifBlank { "停止中" }
        subText.text = when {
            MirrorState.running && st == MirrorState.Status.STREAMING -> "遅延 約200ms ・ AirPlay 音量 ${MirrorState.volume}"
            MirrorState.running && st == MirrorState.Status.PAUSED -> "別のアプリに戻ると自動で再開します"
            selected == null -> "右の一覧から送信先を選んでください"
            else -> "「${selected!!.displayName}」へ送信できます"
        }

        val running = MirrorState.running
        startBtn.text = if (running) "■  停止" else "▶  開始"
        startBtn.setTextColor(if (running) Palette.TEXT else Color.WHITE)
        val canStart = running || selected != null
        startBtn.alpha = if (canStart) 1f else 0.4f
        startBtn.isEnabled = canStart
        val normal = if (running) cardBg(Palette.SURFACE, Palette.RED, 2.5f, 26f)
        else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.ACCENT, Palette.ACCENT2)).apply { cornerRadius = dp(26f) }
        startBtn.background = focusableBg(normal, focusFill = if (running) Palette.SURFACE_HI else Palette.ACCENT, radiusDp = 26f)

        volumeValue.text = "${MirrorState.volume}"
        volumeBar.value = MirrorState.volume
        tAuto.checked = prefs.getBoolean("auto", true)
        tBoot.checked = prefs.getBoolean("boot", true)
        tNetflix.checked = prefs.getBoolean("pause_netflix", true)
        tKeys.checked = prefs.getBoolean("volkeys", true)
        logToggle.text = if (showLog) "ログを隠す" else "ログを表示"
        logScroll.visibility = if (showLog) View.VISIBLE else View.GONE
        if (showLog) {
            logText.text = MirrorState.text()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else medium()
    }
}
