package com.zyagasan.projectorairplay

import java.io.ByteArrayOutputStream

/** 48kHz → 44.1kHz のステレオ 16bit リトルエンディアン PCM の線形補間リサンプラ。 */
class Resampler(inRate: Int = 48000, outRate: Int = 44100) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0
    private var lastL = 0
    private var lastR = 0

    fun process(input: ByteArray, len: Int): ByteArray {
        val n = len / 4
        if (n == 0) return ByteArray(0)
        val l = IntArray(n + 1)
        val r = IntArray(n + 1)
        l[0] = lastL; r[0] = lastR
        for (i in 0 until n) {
            val o = i * 4
            l[i + 1] = ((input[o].toInt() and 0xff) or (input[o + 1].toInt() shl 8)).toShort().toInt()
            r[i + 1] = ((input[o + 2].toInt() and 0xff) or (input[o + 3].toInt() shl 8)).toShort().toInt()
        }
        val out = ByteArrayOutputStream((n * 4 / step).toInt() + 8)
        var p = pos
        while (p < n) {
            val i = p.toInt()
            val f = p - i
            val ol = (l[i] + (l[i + 1] - l[i]) * f).toInt()
            val or = (r[i] + (r[i + 1] - r[i]) * f).toInt()
            out.write(ol and 0xff); out.write((ol shr 8) and 0xff)
            out.write(or and 0xff); out.write((or shr 8) and 0xff)
            p += step
        }
        pos = p - n
        lastL = l[n]; lastR = r[n]
        return out.toByteArray()
    }
}
