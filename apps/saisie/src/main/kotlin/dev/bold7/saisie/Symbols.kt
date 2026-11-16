package dev.bold7.saisie

/**
 * OS 7's Sym grid (sim-303 to sim-305, C): two pages, each three rows of glyphs laid over the
 * keyboard's keys (QWERTYUIOP, ASDFGHJKL, ZXCVBNM and SYM). The last glyph of the third row, over
 * the SYM key, is the arrow that turns the page. A letter types the glyph above its key.
 */
object Symbols {
    val ROWS = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
    const val NEXT = '▸'
    const val PREVIOUS = '◂'
    val PAGES = listOf(
        listOf("[<{«~=±“^%", "]>}»\\÷`”&", "\$€¥£•|¤$NEXT"),
        listOf("#123()_-+@", "*456/:;'\"", "789?!,.$PREVIOUS"),
    )

    private fun isFlip(c: Char) = c == NEXT || c == PREVIOUS

    /** The glyph over [letter]'s key on [page], or null (not a letter, or the SYM key). */
    fun of(letter: Char, page: Int): Char? {
        val l = letter.uppercaseChar()
        for (r in ROWS.indices) {
            val c = ROWS[r].indexOf(l)
            if (c >= 0) return PAGES[page][r][c]
        }
        return null
    }

    /** The focused glyph; the trackpad moves it, stopping at the grid's edges. */
    data class Focus(val page: Int, val row: Int, val col: Int) {
        val symbol get() = PAGES[page][row][col]
        val flips get() = isFlip(symbol)

        fun move(dx: Int, dy: Int): Focus {
            val r = (row + dy).coerceIn(0, PAGES[page].size - 1)
            return copy(row = r, col = (col + dx).coerceIn(0, PAGES[page][r].length - 1))
        }

        /** The other page, the focus on its arrow. */
        fun flipped(): Focus = Focus(1 - page, 2, PAGES[0][2].length - 1)
    }
}
