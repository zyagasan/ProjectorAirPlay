package com.zyagasan.projectorairplay

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 48kHz → 44.1kHz のステレオ 16bit リトルエンディアン PCM のリサンプラ。
 * 48000:44100 = 160:147 なので、147 位相の窓付き sinc(Kaiser)フィルタで補間なしに正確に変換する。
 * 線形補間と違い、高域でも歪み・折り返しノイズがほぼ出ない(10kHz で 22dB → 91dB)。
 */
class Resampler(private val taps: Int = 64, cutoff: Double = 0.42, beta: Double = 7.0) {
    private val l = 147               // 出力側の位相数
    private val m = 160               // 入力を進める量
    private val half = taps / 2
    private val coef = Array(l) { FloatArray(taps) }

    // 入力の履歴(前回の末尾 taps サンプル)と、次に作る出力の位置
    private var histL = FloatArray(taps)
    private var histR = FloatArray(taps)
    private var phasePos = 0L          // 次の出力の位置(入力サンプル × l 単位)。履歴先頭 = 0

    init {
        val i0Beta = bessel0(beta)
        for (ph in 0 until l) {
            var sum = 0.0
            val tmp = DoubleArray(taps)
            for (k in 0 until taps) {
                val d = (half - 1 - k) + ph.toDouble() / l
                val s = if (d == 0.0) 2 * cutoff else sin(2 * PI * cutoff * d) / (PI * d)
                val w = if (abs(d) < half) bessel0(beta * sqrt(1 - (d / half) * (d / half))) / i0Beta else 0.0
                tmp[k] = s * w
                sum += tmp[k]
            }
            for (k in 0 until taps) coef[ph][k] = (tmp[k] / sum).toFloat()   // 直流ゲインを 1 に揃える
        }
        phasePos = (half - 1).toLong() * l   // 先頭 half-1 サンプルは履歴(無音)として扱う
    }

    fun process(input: ByteArray, len: Int): ByteArray {
        val n = len / 4
        if (n == 0) return ByteArray(0)
        // 履歴 + 今回の入力 をつなげた作業配列
        val total = taps + n
        val xl = FloatArray(total)
        val xr = FloatArray(total)
        System.arraycopy(histL, 0, xl, 0, taps)
        System.arraycopy(histR, 0, xr, 0, taps)
        for (i in 0 until n) {
            val o = i * 4
            xl[taps + i] = ((input[o].toInt() and 0xff) or (input[o + 1].toInt() shl 8)).toShort().toFloat()
            xr[taps + i] = ((input[o + 2].toInt() and 0xff) or (input[o + 3].toInt() shl 8)).toShort().toFloat()
        }

        val out = ByteArray(((n.toLong() * l / m) + 4).toInt() * 4)
        var oi = 0
        var pos = phasePos
        // 出力 1 サンプルに必要な範囲: 入力位置 i の周り [i-half+1, i+half]。配列の終端を超えたら次回へ持ち越す
        while (true) {
            val i = (pos / l).toInt()
            if (i + half >= total) break
            val c = coef[(pos % l).toInt()]
            var sl = 0f
            var sr = 0f
            val base = i - half + 1
            for (k in 0 until taps) {
                sl += xl[base + k] * c[k]
                sr += xr[base + k] * c[k]
            }
            val vl = Math.round(sl).coerceIn(-32768, 32767)
            val vr = Math.round(sr).coerceIn(-32768, 32767)
            out[oi++] = vl.toByte(); out[oi++] = (vl shr 8).toByte()
            out[oi++] = vr.toByte(); out[oi++] = (vr shr 8).toByte()
            pos += m
        }
        // 次回: 今回の末尾 taps サンプルを履歴にして、位置を n サンプル分だけ戻す
        System.arraycopy(xl, n, histL, 0, taps)
        System.arraycopy(xr, n, histR, 0, taps)
        phasePos = pos - n.toLong() * l
        return out.copyOf(oi)
    }

    private fun bessel0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4
        var k = 1
        while (term > 1e-12 * sum) {
            term *= q / (k.toDouble() * k)
            sum += term
            k++
        }
        return sum
    }
}
