package dev.bold7.ui

// OS 7 draws the 45 emoticons of its SMS app and has no colour emoji (docs/BBOS7-VISUEL.md §16): the common emoji a message from Android carries are read as the
// nearest emoticon (D: no source says which one a 9900 would show, the choice is by the image,
// sim-145, C). An emoji with no near emoticon (animals, flags, objects) stays as it came, drawn by
// Android's font: OS 7's own fallback for a glyph its font lacks is not captured. Pure.

/** Emoticon name to the code points read as it; the emoticons' own emoji are [Emoticons]'s. */
private val ALIASES: Map<String, IntArray> = mapOf(
    "Smile" to intArrayOf(0x1F60A, 0x263A, 0x1F60C, 0x1F642),
    "Big Smile" to intArrayOf(0x1F600, 0x1F603, 0x1F604, 0x1F638, 0x1F63A),
    "ROTFL" to intArrayOf(0x1F602, 0x1F639),
    "Tongue Out" to intArrayOf(0x1F60B, 0x1F61C, 0x1F61D, 0x1F92A, 0x1F924),
    "Kiss" to intArrayOf(0x1F618, 0x1F61A, 0x1F619, 0x1F48B),
    "Love Struck" to intArrayOf(0x1F63B, 0x1F929),
    "Cool" to intArrayOf(0x1F576),
    "Crying" to intArrayOf(0x1F622, 0x1F62D, 0x1F63F),
    "Whew!" to intArrayOf(0x1F613, 0x1F625, 0x1F630),
    "Sad" to intArrayOf(0x1F61E, 0x1F614, 0x1F61F, 0x1F623, 0x1F616, 0x1F629, 0x1F62B, 0x2639, 0x1F97A),
    "Angry" to intArrayOf(0x1F620, 0x1F92C, 0x1F624),
    "Surprised" to intArrayOf(0x1F631, 0x1F632, 0x1F62F, 0x1F627, 0x1F626, 0x1F628),
    "Embarrassed" to intArrayOf(0x1F62C, 0x1F92D),
    "Straight Face" to intArrayOf(0x1F611, 0x1F636),
    "Confused" to intArrayOf(0x1F635),
    "Sarcastic" to intArrayOf(0x1F643),
    "Yawn" to intArrayOf(0x1F634, 0x1F62A),
    "Sick" to intArrayOf(0x1F92E, 0x1F927, 0x1F637, 0x1F912, 0x1F915),
    "Lips Are Sealed" to intArrayOf(0x1F92B),
    "Devil" to intArrayOf(0x1F47F),
    "Yes" to intArrayOf(0x1F44C, 0x270C, 0x1F44F, 0x1F64C, 0x270A, 0x1F44A),
    "Talk to the Hand" to intArrayOf(0x1F590, 0x1F91A),
    "On the Phone" to intArrayOf(0x260E, 0x1F4F1),
    "Heart" to intArrayOf(0x2665, 0x2764, 0x2763, 0x1F49F, 0x1FA75, 0x1FA76, 0x1FA77, 0x1F495, 0x1F496, 0x1F497, 0x1F493, 0x1F498, 0x1F49D, 0x1F49E, 0x1F9E1, 0x1F49B, 0x1F49A, 0x1F499, 0x1F49C, 0x1F5A4, 0x1F90D, 0x1F90E),
    "Gold Star" to intArrayOf(0x1F31F),
    "Beer" to intArrayOf(0x1F37B),
    "Dancing" to intArrayOf(0x1F57A),
    "Party" to intArrayOf(0x1F389),
)

private val SKIN_TONES = 0x1F3FB..0x1F3FF
private const val VARIATION = 0xFE0F

private val byCodePoint: Map<Int, String> by lazy {
    // The emoticons' own emoji too, so that a skin tone after one goes as well.
    Emoticons.ALL.associate { it.emoji.codePointAt(0) to it.emoji } + ALIASES.flatMap { (name, cps) ->
        val emoji = Emoticons.ALL.first { it.name == name }.emoji
        cps.map { it to emoji }
    }.toMap()
}

object EmojiAliases {
    /** The emoticon names the table points at, for the test. */
    fun targets(): List<String> = ALIASES.keys.toList()
}

/** [text] with each aliased emoji (and the skin tone or U+FE0F after it) replaced by its emoticon's emoji; [text] itself if none. */
fun osEmoji(text: String): String {
    // Every emoji is U+2600 and after, or a surrogate pair.
    if (text.none { it >= '☀' }) return text
    val out = StringBuilder(text.length)
    var i = 0
    var changed = false
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        val emoji = byCodePoint[cp]
        if (emoji == null) {
            out.appendCodePoint(cp)
            continue
        }
        changed = true
        out.append(emoji)
        while (i < text.length && text.codePointAt(i).let { it == VARIATION || it in SKIN_TONES }) i += Character.charCount(text.codePointAt(i))
    }
    return if (changed) out.toString() else text
}
