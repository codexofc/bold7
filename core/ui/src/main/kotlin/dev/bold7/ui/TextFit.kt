package dev.bold7.ui

import android.graphics.Paint
import java.text.BreakIterator

/** OS 7's mark for a text that does not fit its place (an ellipsis at the end of a row, a title, a menu entry). */
const val ELLIPSIS = "…"

/**
 * [text] as it fits in [room] px, [measure] giving a string's advance: whole when it fits, else its
 * longest start followed by [ELLIPSIS] that fits, cut between user-perceived characters (never
 * inside a surrogate pair, an accent's mark or an emoji sequence), the spaces before the mark
 * dropped; empty when not even the mark fits. The one place that decides how a text is cut.
 */
fun fit(text: String, room: Float, measure: (String) -> Float): String {
    if (measure(text) <= room) return text
    if (measure(ELLIPSIS) > room) return ""
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    val ends = ArrayList<Int>()
    var e = it.next()
    while (e != BreakIterator.DONE) {
        ends += e
        e = it.next()
    }
    // The longest prefix, in whole characters, whose cut text fits: a wider prefix is never narrower.
    var lo = 0
    var hi = ends.size - 1
    var best = ""
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val cut = text.substring(0, ends[mid]).trimEnd() + ELLIPSIS
        if (measure(cut) <= room) {
            best = cut
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return best.ifEmpty { ELLIPSIS }
}

/** [text] fitted to [room] px in this paint's font. */
fun Paint.fit(text: String, room: Float): String = fit(text, room, ::measureText)

/**
 * The size, at most [size], at which a text that is [wide] px wide at [size] takes no more than
 * [room] px: a label that must stay whole (an hour, a month and year in a narrow column) is
 * drawn smaller rather than cut or touching its neighbour. Text widths are linear in the size.
 */
fun fitSize(size: Float, wide: Float, room: Float): Float = if (wide <= room || wide <= 0f || room <= 0f) size else size * room / wide
