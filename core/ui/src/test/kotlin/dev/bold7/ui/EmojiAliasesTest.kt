package dev.bold7.ui
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class EmojiAliasesTest {
    private fun emoticon(name: String) = Emoticons.ALL.first { it.name == name }.emoji

    @Test fun everyAliasTargetsAnEmoticonOfTheStrip() {
        val names = Emoticons.ALL.map { it.name }.toSet()
        assertEquals(emptySet(), EmojiAliases.targets().toSet() - names)
    }

    @Test fun aCommonEmojiBecomesTheNearestEmoticon() {
        // A common case: the tears of joy of the lists.
        assertEquals("a " + emoticon("ROTFL"), osEmoji("a 😂"))
        assertEquals(emoticon("Smile"), osEmoji("😊"))
        assertEquals(emoticon("Heart"), osEmoji("💙"))
        assertEquals(emoticon("Yes"), osEmoji("👌"))
    }

    @Test fun theCommonHeartsAreTheHeartEmoticon() {
        // U+2764 is the red heart of names and messages, with or without its variation selector.
        assertEquals("Lisa " + emoticon("Heart"), osEmoji("Lisa \u2764\uFE0F"))
        assertEquals(emoticon("Heart"), osEmoji("\u2764"))
        for (cp in intArrayOf(0x2763, 0x1F49F, 0x1F9E1, 0x1F49B, 0x1F49A, 0x1F499, 0x1F49C, 0x1F5A4, 0x1F90D, 0x1F90E, 0x1FA75, 0x1FA76, 0x1FA77))
            assertEquals(emoticon("Heart"), osEmoji(String(Character.toChars(cp))), cp.toString(16))
    }

    @Test fun theVariationSelectorAndSkinTonesGoWithTheEmoji() {
        assertEquals(emoticon("Smile"), osEmoji("☺️"))
        assertEquals(emoticon("Yes"), osEmoji("👍🏽"))
        assertEquals(emoticon("Yes") + emoticon("Yes"), osEmoji("👏🏻👏"))
    }

    @Test fun anEmoticonsOwnEmojiAndPlainTextAreLeftAlone() {
        val plain = "Salut :) 12h"
        assertSame(plain, osEmoji(plain))
        for (e in Emoticons.ALL) assertEquals(e.emoji, osEmoji(e.emoji), e.name)
    }

    @Test fun anEmojiWithoutEquivalentStaysAsTyped() {
        // A dog: no emoticon, no source for what OS 7 draws (not sourced).
        assertEquals("🐶", osEmoji("🐶"))
    }

    @Test fun theMappedEmojiAreFoundAsEmoticons() {
        val found = Emoticons.find(osEmoji("ok 😂😍"), shortcuts = false)
        assertEquals(listOf(Emoticons.ALL.indexOfFirst { it.name == "ROTFL" }, Emoticons.ALL.indexOfFirst { it.name == "Love Struck" }), found.map { it.index })
    }
}
