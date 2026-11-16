package dev.bold7.mail

// An HTML mail read as plain text: no WebView, so nothing of the mail can reach the
// network (no remote image, no tracking pixel, no link followed) nor run (no script). Pure. What
// the 9900 calls "Get Plain Text" (gsm 032, C), applied always (D).

private val DROPPED = Regex("<!--.*?-->|<(script|style|head|title)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SPACES = Regex("\\s+")
private val BREAKS = Regex("<br\\b[^>]*>|</(p|div|tr|li|h[1-6]|blockquote|table|ul|ol|pre)\\s*>", RegexOption.IGNORE_CASE)
private val TAGS = Regex("<[^>]*>")
private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
private val BLANK_LINES = Regex("\n{3,}")

/** The named entities a mail commonly carries; any other stays as written. */
private val NAMED = mapOf(
    "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "agrave" to "à", "acirc" to "â", "ccedil" to "ç",
    "ugrave" to "ù", "ucirc" to "û", "ocirc" to "ô", "icirc" to "î", "iuml" to "ï", "euml" to "ë",
    "Eacute" to "É", "Egrave" to "È", "Agrave" to "À", "Ccedil" to "Ç",
    "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "laquo" to "«", "raquo" to "»",
    "hellip" to "…", "mdash" to "—", "ndash" to "–", "euro" to "€", "copy" to "©", "reg" to "®",
    "trade" to "™", "middot" to "·", "bull" to "•", "deg" to "°",
)

private fun decode(entity: MatchResult): String {
    val name = entity.groupValues[1]
    val code = when {
        name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)
        name.startsWith("#") -> name.substring(1).toIntOrNull()
        else -> return NAMED[name] ?: entity.value
    }
    return if (code != null && Character.isValidCodePoint(code) && code != 0) String(Character.toChars(code)) else entity.value
}

/**
 * [html] as text: head, styles, scripts and comments dropped, the source's line breaks spaces (as
 * a browser shows them), a line after each break and each block, tags gone, entities decoded,
 * each line trimmed, at most one blank line in a row. A link keeps its text, not its address (D).
 */
fun htmlToText(html: String): String {
    val flat = SPACES.replace(DROPPED.replace(html, " "), " ")
    val text = ENTITY.replace(TAGS.replace(BREAKS.replace(flat, "\n"), ""), ::decode).replace('\u00A0', ' ')
    return BLANK_LINES.replace(text.lines().joinToString("\n") { it.trim() }, "\n\n").trim()
}
