package dev.bold7.ui

// The emoticons of OS 7's text messages (docs/BBOS7-VISUEL.md §16): the 45 of the
// SMS app, in the order of its strips (smileys_45x45.png, smileys_27x27.png of net_rim_bb_sms, A),
// their names as Add Smiley titles them (sim-145, sim-147, sim-152, C) and the texts its parser
// reads (the strings of net_rim_bb_sms.cod next to "Invalid character found while parsing
// smilies", A). An SMS, MMS or RCS carries no BlackBerry image: what leaves is the nearest
// Unicode emoji (D), which the thread and the rows of the lists draw back with OS 7's image
// (here for both). Pure.

/** One emoticon: [shortcuts] its texts, the first the one its title shows; [emoji] what is sent. */
class Emoticon(val name: String, val shortcuts: List<String>, val emoji: String) {
    val title: String get() = "${shortcuts.first()} $name"
}

object Emoticons {
    private fun e(name: String, shortcuts: String, emoji: String) = Emoticon(name, shortcuts.split(' '), emoji)

    /** In the strips' order: [ALL]'s index is the image's cell. */
    val ALL = listOf(
        e("Smile", ":) :-) =-) =)", "\uD83D\uDE42"), // 🙂
        e("Wink", ";) ;-)", "\uD83D\uDE09"), // 😉
        e("Sad", ":( :-( =-( =(", "\uD83D\uDE41"), // 🙁
        e("Angry", ">:O >:-O >=-O >=O", "\uD83D\uDE21"), // 😡, the image is red
        e("Big Smile", ":D :-D =-D", "\uD83D\uDE01"), // 😁, its teeth
        e("Crying", ":'( ='(", "\uD83D\uDE2D"), // 😭, its streams of tears
        e("Surprised", ":O :-O =-O =O", "\uD83D\uDE2E"), // 😮
        e("Tongue Out", ":-p =-p =p", "\uD83D\uDE1B"), // 😛
        e("Cool", "B-)", "\uD83D\uDE0E"), // 😎
        e("Lips Are Sealed", ":x :-x =-x =x", "\uD83E\uDD10"), // 🤐
        e("Confused", ":s :-s =-s =s", "\uD83D\uDE15"), // 😕
        e("Embarrassed", ":$ :-$ =-$ =$", "\uD83D\uDE33"), // 😳
        e("Kiss", ":* :-* =-* =*", "\uD83D\uDE17"), // 😗, its red lips
        e("Huh?", ":/ :-/ =-/ =/", "\uD83E\uDEE4"), // 🫤, the slanted mouth of ":/"
        e("Heart", "<3", "\u2764\uFE0F"), // ❤️
        e("Yes", "(y) (Y)", "\uD83D\uDC4D"), // 👍
        e("No", "(n) (N)", "\uD83D\uDC4E"), // 👎
        e("Sick", ":& :-& =-& =&", "\uD83E\uDD22"), // 🤢, green
        e("Sarcastic", ":> :-> =->", "\uD83D\uDE0F"), // 😏
        e("Laughing", "=D", "\uD83D\uDE06"), // 😆
        e("Love Struck", "<3<3", "\uD83D\uDE0D"), // 😍
        e("Eyelashes", ";;)", "\uD83E\uDD70"), // 🥰, no emoji bats its eyelashes
        e("ROTFL", "=))", "\uD83E\uDD23"), // 🤣
        e("Raised Eyebrow", "/:) /=)", "\uD83E\uDD28"), // 🤨
        e("Whew!", "#:-s #=-s", "\uD83D\uDE05"), // 😅
        e("Straight Face", ":| =|", "\uD83D\uDE10"), // 😐
        e("Big Hug", "({})", "\uD83E\uDD17"), // 🤗
        e("Talk to the Hand", ">:/ >=/", "\u270B"), // ✋
        e("Broken Heart", "</3", "\uD83D\uDC94"), // 💔
        e("Angel", "O:) O-:) O-=) O=)", "\uD83D\uDE07"), // 😇
        e("Nerd", "*nerd*", "\uD83E\uDD13"), // 🤓
        e("Yawn", "(=| *yawn*", "\uD83E\uDD71"), // 🥱
        e("Thinking", "=-? *thinking*", "\uD83E\uDD14"), // 🤔
        e("Eyes Rolling", "8-| *eyesrolling*", "\uD83D\uDE44"), // 🙄
        e("Party", "<=-P <=-p *party*", "\uD83E\uDD73"), // 🥳
        e("Devil", ">=)", "\uD83D\uDE08"), // 😈
        e("On the Phone", "=)] *onthephone*", "\uD83D\uDCDE"), // 📞
        e("Call Me", "=-c *callme*", "\uD83E\uDD19"), // 🤙
        e("Can't Watch", "X_X", "\uD83D\uDE48"), // 🙈, a hand over the eyes
        e("Gold Star", "(*)", "\u2B50"), // ⭐
        e("Dancing", "\\=D/", "\uD83D\uDC83"), // 💃
        e("Waiting", "*...* *waiting*", "\uD83D\uDD52"), // 🕒, the image is a clock
        e("Beer", "*beer*", "\uD83C\uDF7A"), // 🍺
        e("Coffee", "~o) *coffee*", "\u2615"), // ☕
        e("Not Interested", "3-|", "\uD83D\uDE12"), // 😒
    )

    /** An emoticon in a text: [start] to [end] (exclusive) draws [index]'s image. */
    data class Found(val start: Int, val end: Int, val index: Int)

    private const val VARIATION = '\uFE0F'

    // Emojis without their variation selector, the longest first; shortcuts the longest first.
    private val emojis = ALL.mapIndexed { i, e -> e.emoji.trimEnd(VARIATION) to i }.sortedByDescending { it.first.length }
    private val texts = ALL.flatMapIndexed { i, e -> e.shortcuts.map { it to i } }.sortedByDescending { it.first.length }

    /**
     * The emoticons of [text], left to right: its emojis, with or without U+FE0F after them; and
     * with [shortcuts] the texts the 9900's parser turns into images (A), each between the start
     * or a space and the end, a space or a punctuation mark (D: the parser's own rule is in its
     * bytecode, not read; this keeps "http://" and "a:)b" as text).
     * Simplification: tries each entry at each character, about 150 per character; a trie if long
     * texts ever show up in a measurement.
     */
    fun find(text: CharSequence, shortcuts: Boolean): List<Found> {
        val out = mutableListOf<Found>()
        var i = 0
        while (i < text.length) {
            // Every emoji is outside ASCII and Latin text (U+2600 and after, or a surrogate pair).
            if (!shortcuts && text[i] < '\u2600') {
                i++
                continue
            }
            val hit = emojiAt(text, i) ?: if (shortcuts) shortcutAt(text, i) else null
            if (hit == null) {
                i++
                continue
            }
            out += hit
            i = hit.end
        }
        return out
    }

    private fun emojiAt(text: CharSequence, i: Int): Found? {
        val (e, index) = emojis.firstOrNull { text.startsWith(it.first, i) } ?: return null
        var end = i + e.length
        if (end < text.length && text[end] == VARIATION) end++
        return Found(i, end, index)
    }

    private fun shortcutAt(text: CharSequence, i: Int): Found? {
        if (i > 0 && !text[i - 1].isWhitespace()) return null
        val (s, index) = texts.firstOrNull { text.startsWith(it.first, i) && endsWord(text, i + it.first.length) } ?: return null
        return Found(i, i + s.length, index)
    }

    private fun endsWord(text: CharSequence, end: Int) = end == text.length || text[end].isWhitespace() || text[end] in ".,!?;:"
}
