package com.zyagasan.projectorairplay

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {
    /** freq の正弦波を 48k で作って 44.1k に変換し、変換後の信号とノイズの比(dB)を返す。 */
    private fun snrDb(freq: Double, chunk: Int): Pair<Double, Int> {
        val inRate = 48000.0
        val n = 48000
        val pcm = ByteArray(n * 4)
        for (i in 0 until n) {
            val v = (0.5 * 32767 * sin(2 * PI * freq * i / inRate)).toInt()
            pcm[i * 4] = v.toByte(); pcm[i * 4 + 1] = (v shr 8).toByte()
            pcm[i * 4 + 2] = v.toByte(); pcm[i * 4 + 3] = (v shr 8).toByte()
        }
        val rs = Resampler()
        val out = java.io.ByteArrayOutputStream()
        var o = 0
        while (o < pcm.size) {                      // 実機と同じく小さなチャンクで流す(状態の持ち越しも検証)
            val len = minOf(chunk, pcm.size - o)
            out.write(rs.process(pcm.copyOfRange(o, o + len), len))
            o += len
        }
        val b = out.toByteArray()
        val m = b.size / 4
        val y = DoubleArray(m) { i -> ((b[i * 4].toInt() and 0xff) or (b[i * 4 + 1].toInt() shl 8)).toShort().toDouble() }
        // 周波数 freq の正弦波を最小二乗で当てはめ、残差をノイズとみなす(両端は過渡部分なので除く)
        val from = 2000
        val to = m - 2000
        var sSS = 0.0; var sCC = 0.0; var sSC = 0.0; var sYS = 0.0; var sYC = 0.0
        for (i in from until to) {
            val t = 2 * PI * freq * i / 44100.0
            val s = sin(t); val c = kotlin.math.cos(t)
            sSS += s * s; sCC += c * c; sSC += s * c; sYS += y[i] * s; sYC += y[i] * c
        }
        val det = sSS * sCC - sSC * sSC
        val a = (sYS * sCC - sYC * sSC) / det
        val bb = (sYC * sSS - sYS * sSC) / det
        var sig = 0.0; var err = 0.0
        for (i in from until to) {
            val t = 2 * PI * freq * i / 44100.0
            val fit = a * sin(t) + bb * kotlin.math.cos(t)
            sig += fit * fit; err += (y[i] - fit) * (y[i] - fit)
        }
        return 10 * log10(sig / err) to m
    }

    @Test fun tonesStayCleanAcrossChunkSizes() {
        for (f in listOf(1000.0, 10000.0, 15000.0)) {
            for (chunk in listOf(8192, 4096 + 12, 352 * 4)) {
                val (snr, _) = snrDb(f, chunk)
                println("freq=$f chunk=$chunk snr=%.1f dB".format(snr))
                assertTrue("$f Hz chunk $chunk SNR $snr", snr > 75.0)
            }
        }
    }

    @Test fun outputLengthMatchesRatio() {
        val (_, m) = snrDb(1000.0, 8192)
        // 1 秒分(48000 サンプル)が約 44100 サンプルになる
        assertEquals(44100.0, m.toDouble(), 100.0)
    }
}
