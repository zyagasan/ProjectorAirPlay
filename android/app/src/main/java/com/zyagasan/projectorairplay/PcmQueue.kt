package com.zyagasan.projectorairplay

import java.util.ArrayDeque

/**
 * 取得側と送信側の間のバッファ。溜まりすぎたら古い音を捨てて遅延が増え続けるのを防ぐ。
 * 起動直後に取得側が溜め込んだ音が一気に届く(バースト)場合にも効く。
 */
class PcmQueue(private val maxBytes: Int, private val targetBytes: Int) {
    private val chunks = ArrayDeque<ByteArray>()
    private var total = 0
    private var closed = false
    @Volatile var droppedBytes = 0L

    @Synchronized fun put(data: ByteArray) {
        if (data.isEmpty()) return
        chunks.add(data); total += data.size
        if (total > maxBytes) {
            while (total > targetBytes && chunks.size > 1) {
                val d = chunks.poll()!!
                total -= d.size; droppedBytes += d.size
            }
        }
        (this as Object).notifyAll()
    }

    /** 最大 max バイトを取り出す。データが来るまで待つ。閉じられたら null。 */
    @Synchronized fun take(max: Int): ByteArray? {
        while (chunks.isEmpty()) {
            if (closed) return null
            (this as Object).wait()
        }
        val first = chunks.peek()!!
        return if (first.size <= max) {
            chunks.poll(); total -= first.size; first
        } else {
            chunks.poll()
            val head = first.copyOfRange(0, max)
            chunks.addFirst(first.copyOfRange(max, first.size))
            total -= max
            head
        }
    }

    @Synchronized fun close() { closed = true; (this as Object).notifyAll() }
    @Synchronized fun bytes(): Int = total
}
