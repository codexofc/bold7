package dev.bold7.memopad

import dev.bold7.design.ListDimens

/** The room of the texts MemoPad Options draws by hand pure: [dev.bold7.ui.fit] cuts to it. */
object Rooms {
    /** A group's title: between the rows' margins. */
    fun groupTitle(width: Int): Float = (width - 2 * ListDimens.TEXT_X).coerceAtLeast(0).toFloat()

    /** A value at the right: from the right margin back to the middle of the row, so the label keeps its half. */
    fun value(width: Int): Float = (width / 2 - ListDimens.CHECK_RIGHT).coerceAtLeast(0).toFloat()

    /** A label left of a value [valueWidth] px wide: up to it, a margin between. */
    fun label(width: Int, valueWidth: Float): Float =
        (width - ListDimens.CHECK_RIGHT - valueWidth - 2 * ListDimens.TEXT_X).coerceAtLeast(0f)
}
