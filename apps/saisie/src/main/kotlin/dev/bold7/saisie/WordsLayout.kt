package dev.bold7.saisie

/**
 * The widths of Predictive's cells when the words together would be wider than the screen,
 * pure. A list that fits is left as it is; else the widest cells are cut down to one common width, the
 * largest at which the whole frame (cells, the 2 px between, the frame's own [pad]) fits [room]; the
 * view cuts their words by `fit`. Nothing is stored: it is computed from the words on show.
 */
object WordsLayout {
    fun cells(natural: IntArray, room: Int, separator: Int, pad: Int): IntArray {
        val avail = room - separator * (natural.size - 1).coerceAtLeast(0) - 2 * pad
        if (natural.sum() <= avail || natural.isEmpty()) return natural
        var lo = 0
        var hi = natural.max()
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (natural.sumOf { minOf(it, mid) } <= avail) lo = mid else hi = mid - 1
        }
        return IntArray(natural.size) { minOf(natural[it], lo) }
    }
}
