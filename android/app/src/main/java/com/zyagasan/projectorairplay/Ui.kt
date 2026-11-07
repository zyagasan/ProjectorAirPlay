package com.zyagasan.projectorairplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** 画面全体で使う色。 */
object Palette {
    val BG_TOP = Color.parseColor("#090D12")
    val BG_BOTTOM = Color.parseColor("#101A27")
    val SURFACE = Color.parseColor("#17202B")
    val SURFACE_HI = Color.parseColor("#1F2B3A")
    val SELECTED = Color.parseColor("#17335A")
    val ACCENT = Color.parseColor("#3B82F6")
    val ACCENT2 = Color.parseColor("#22D3EE")
    val TEXT = Color.parseColor("#F2F6FA")
    val MUTED = Color.parseColor("#8A98A9")
    val GREEN = Color.parseColor("#34D399")
    val AMBER = Color.parseColor("#FBBF24")
    val RED = Color.parseColor("#F87171")
    val BLUE = Color.parseColor("#60A5FA")
}

fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** 角丸の面。フォーカスすると白枠 + 少し拡大で、リモコン操作でも今どこにいるか分かる。 */
fun Context.cardBg(fill: Int, stroke: Int = Color.TRANSPARENT, strokeDp: Float = 0f, radiusDp: Float = 18f): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dp(radiusDp); setColor(fill)
        if (strokeDp > 0) setStroke(dp(strokeDp).toInt(), stroke)
    }

fun Context.focusableBg(normal: GradientDrawable, focusFill: Int = Palette.SURFACE_HI, radiusDp: Float = 18f): StateListDrawable =
    StateListDrawable().apply {
        val focused = cardBg(focusFill, Color.WHITE, 3f, radiusDp)
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(), normal)
    }

/** フォーカスで 1.04 倍に拡大するアニメーション(親が子をクリップしないこと)。 */
fun View.scaleOnFocus(scale: Float = 1.04f) {
    val prev = onFocusChangeListener
    setOnFocusChangeListener { v, has ->
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(120).start()
        prev?.onFocusChange(v, has)
    }
}

fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

/** 送信状態を大きく見せる円。送信中はイコライザが動き、接続中は円弧が回る。 */
class StatusOrb(ctx: Context) : View(ctx) {
    enum class State { IDLE, CONNECTING, STREAMING, PAUSED, ERROR }

    var state = State.IDLE
        set(v) { if (field != v) { field = v; invalidate() } }

    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedFraction; invalidate() }
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    private fun color() = when (state) {
        State.IDLE -> Palette.MUTED
        State.CONNECTING -> Palette.AMBER
        State.STREAMING -> Palette.GREEN
        State.PAUSED -> Palette.BLUE
        State.ERROR -> Palette.RED
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.62f
        val col = color()

        // 外側のにじみ
        p.shader = RadialGradient(cx, cy, r * 1.7f, Color.argb(70, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, r * 1.7f, p)
        p.shader = null

        // 送信中は波紋を 2 つ広げる
        if (state == State.STREAMING) {
            for (k in 0..1) {
                val ph = (t + k * 0.5f) % 1f
                p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(3f)
                p.color = Color.argb(((1 - ph) * 110).toInt(), Color.red(col), Color.green(col), Color.blue(col))
                c.drawCircle(cx, cy, r * (1f + 0.45f * ph), p)
            }
        }

        // 円の面と輪郭
        p.style = Paint.Style.FILL
        p.color = Palette.SURFACE
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(5f); p.color = col
        if (state == State.CONNECTING) {
            c.drawCircle(cx, cy, r, p.apply { color = Color.argb(60, Color.red(col), Color.green(col), Color.blue(col)) })
            p.color = col; p.strokeCap = Paint.Cap.ROUND
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), t * 360f, 110f, false, p)
        } else {
            c.drawCircle(cx, cy, r, p)
        }

        // 中身
        p.style = Paint.Style.FILL; p.color = col
        when (state) {
            State.STREAMING, State.IDLE, State.CONNECTING -> drawBars(c, cx, cy, r)
            State.PAUSED -> {
                val w = r * 0.2f; val h = r * 0.7f
                c.drawRoundRect(RectF(cx - w * 1.3f, cy - h / 2, cx - w * 0.3f, cy + h / 2), w / 2, w / 2, p)
                c.drawRoundRect(RectF(cx + w * 0.3f, cy - h / 2, cx + w * 1.3f, cy + h / 2), w / 2, w / 2, p)
            }
            State.ERROR -> {
                p.textAlign = Paint.Align.CENTER; p.textSize = r * 1.1f; p.typeface = Typeface.DEFAULT_BOLD
                c.drawText("!", cx, cy + r * 0.38f, p)
            }
        }
    }

    private val speeds = intArrayOf(2, 3, 1, 3, 2)
    private val phases = floatArrayOf(0f, 0.2f, 0.45f, 0.7f, 0.9f)

    private fun drawBars(c: Canvas, cx: Float, cy: Float, r: Float) {
        val n = 5
        val bw = r * 0.14f
        val gap = r * 0.11f
        val total = n * bw + (n - 1) * gap
        var x = cx - total / 2
        for (i in 0 until n) {
            val h = when (state) {
                State.STREAMING -> r * (0.22f + 0.62f * abs(sin(2 * PI * (t * speeds[i] + phases[i])).toFloat()))
                State.CONNECTING -> r * 0.2f
                else -> bw
            }
            c.drawRoundRect(RectF(x, cy - h / 2, x + bw, cy + h / 2), bw / 2, bw / 2, p)
            x += bw + gap
        }
    }
}

/** 0〜100 の音量バー。フォーカス中は ← → で 5 ずつ、タッチ/マウスでも操作できる。 */
class VolumeBar(ctx: Context) : View(ctx) {
    var value = 0
        set(v) { val nv = v.coerceIn(0, 100); if (nv != field) { field = nv; invalidate() } }
    var onUserChange: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init { isFocusable = true; isClickable = true; scaleOnFocus(1.02f) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = if (event.repeatCount > 3) 10 else 5
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { onUserChange?.invoke(value - step); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { onUserChange?.invoke(value + step); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pad = context.dp(24f)
        val frac = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            requestFocus(); onUserChange?.invoke((frac * 100 / 5).toInt() * 5); return true
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val pad = context.dp(24f)
        val cy = height / 2f
        val th = context.dp(14f)
        val x0 = pad
        val x1 = width - pad
        val xv = x0 + (x1 - x0) * value / 100f

        if (isFocused) {
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dp(3f); p.color = Color.WHITE
            c.drawRoundRect(RectF(1f, 1f, width - 1f, height - 1f), context.dp(18f), context.dp(18f), p)
        }
        p.style = Paint.Style.FILL
        p.color = Palette.SURFACE_HI
        c.drawRoundRect(RectF(x0, cy - th / 2, x1, cy + th / 2), th / 2, th / 2, p)
        p.shader = LinearGradient(x0, 0f, x1, 0f, Palette.ACCENT, Palette.ACCENT2, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(x0, cy - th / 2, xv, cy + th / 2), th / 2, th / 2, p)
        p.shader = null
        val kr = context.dp(if (isFocused) 15f else 12f)
        p.color = Color.argb(60, 255, 255, 255)
        c.drawCircle(xv, cy, kr * 1.6f, p)
        p.color = Color.WHITE
        c.drawCircle(xv, cy, kr, p)
    }
}

/** ラベル + スイッチ。OK ボタンで切り替える。 */
class ToggleRow(ctx: Context, label: String, private val onChange: (Boolean) -> Unit) : LinearLayout(ctx) {
    var checked = false
        set(v) { field = v; sw.invalidate() }
    private val sw = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.style = Paint.Style.FILL
            p.color = if (checked) Palette.ACCENT else Color.parseColor("#3A4757")
            c.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
            p.color = Color.WHITE
            val kr = h / 2 - context.dp(3f)
            c.drawCircle(if (checked) w - h / 2 else h / 2, h / 2, kr, p)
        }
    }

    init {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(18), 0, context.dp(18), 0)
        background = context.focusableBg(context.cardBg(Palette.SURFACE, radiusDp = 14f), radiusDp = 14f)
        isFocusable = true; isClickable = true
        setOnClickListener { checked = !checked; onChange(checked) }
        scaleOnFocus(1.03f)
        addView(TextView(ctx).apply {
            text = label; textSize = 17f; setTextColor(Palette.TEXT); typeface = medium(); maxLines = 2
        }, LayoutParams(0, -2, 1f))
        addView(sw, LayoutParams(context.dp(52), context.dp(30)).apply { leftMargin = context.dp(12) })
    }
}
