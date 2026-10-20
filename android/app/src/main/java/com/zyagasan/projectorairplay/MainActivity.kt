package com.zyagasan.projectorairplay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

/** リモコン(D-pad)で操作する、送信先選択と開始/停止だけのシンプルな画面。 */
class MainActivity : Activity() {
    private lateinit var discovery: RaopDiscovery
    private val targets = mutableListOf<Target>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var list: ListView
    private lateinit var toggle: Button
    private lateinit var volumeText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var selected: Target? = null
    private var volume = 40

    private val prefs get() = getSharedPreferences("prefs", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volume = prefs.getInt("volume", 40).coerceIn(0, MirrorSession.MAX_VOLUME)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 32) }
        root.addView(TextView(this).apply { text = "AirPlay Mirror — 送信先を選んで「開始」"; textSize = 24f })

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, mutableListOf<String>())
        list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> select(targets[pos]) }
            isFocusable = true
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        val down = Button(this).apply { text = "音量 −"; setOnClickListener { setVolume(volume - 5) } }
        val up = Button(this).apply { text = "音量 ＋"; setOnClickListener { setVolume(volume + 5) } }
        volumeText = TextView(this).apply { textSize = 18f; setPadding(24, 0, 24, 0) }
        bar.addView(toggle); bar.addView(down); bar.addView(volumeText); bar.addView(up)
        root.addView(bar)

        logText = TextView(this).apply { textSize = 14f; setTextColor(Color.LTGRAY); typeface = android.graphics.Typeface.MONOSPACE }
        logScroll = ScrollView(this).apply { addView(logText) }
        root.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        discovery = RaopDiscovery(this) { found ->
            targets.clear(); targets.addAll(found)
            adapter.clear(); adapter.addAll(found.map { "${it.displayName}   (${it.host})" })
            val last = prefs.getString("last", null)
            val pick = found.firstOrNull { it.serviceName == selected?.serviceName }
                ?: found.firstOrNull { it.serviceName == last }
            pick?.let { select(it) }
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

    private fun select(t: Target) {
        selected = t
        prefs.edit().putString("last", t.serviceName).apply()
        val i = targets.indexOfFirst { it.serviceName == t.serviceName }
        if (i >= 0) list.setItemChecked(i, true)
        refresh()
    }

    private fun setVolume(v: Int) {
        volume = v.coerceIn(0, MirrorSession.MAX_VOLUME)
        prefs.edit().putInt("volume", volume).apply()
        refresh()
    }

    private fun onToggle() {
        if (MirrorState.running) {
            startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
            return
        }
        val t = selected ?: return
        val i = Intent(this, MirrorService::class.java)
            .putExtra("name", t.serviceName).putExtra("host", t.host).putExtra("port", t.port)
            .putExtra("et", t.et).putExtra("volume", volume)
        startForegroundService(i)
    }

    private fun refresh() {
        toggle.text = if (MirrorState.running) "停止" else "開始"
        toggle.isEnabled = MirrorState.running || selected != null
        volumeText.text = "AirPlay 音量 $volume (最大 ${MirrorSession.MAX_VOLUME})"
        logText.text = MirrorState.text()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }
}
