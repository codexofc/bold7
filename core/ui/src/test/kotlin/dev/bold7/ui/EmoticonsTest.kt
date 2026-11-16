package dev.bold7.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmoticonsTest {
    private fun at(name: String) = Emoticons.ALL.indexOfFirst { it.name == name }

    @Test fun theFortyFiveOfTheStripInItsOrderWithTheirTitles() {
        assertEquals(45, Emoticons.ALL.size)
        // sim-145, sim-147, sim-152 (C): the title is the first shortcut and the name.
        assertEquals(":) Smile", Emoticons.ALL[0].title)
        assertEquals(">:O Angry", Emoticons.ALL[3].title)
        assertEquals("<3<3 Love Struck", Emoticons.ALL[20].title)
        assertEquals("3-| Not Interested", Emoticons.ALL[44].title)
        // One emoji each, never twice: the bubbles find their image back from it.
        assertEquals(45, Emoticons.ALL.map { it.emoji }.toSet().size)
        val shortcuts = Emoticons.ALL.flatMap { it.shortcuts }
        assertEquals(shortcuts.size, shortcuts.toSet().size)
    }

    @Test fun theEmojiThatLeavesForEachOne() {
        assertEquals("🙂", Emoticons.ALL[0].emoji) // 🙂
        assertEquals("❤️", Emoticons.ALL[at("Heart")].emoji) // ❤️, in its emoji form
        assertEquals("🤙", Emoticons.ALL[at("Call Me")].emoji) // 🤙
    }

    @Test fun emojisSentOrReceivedAreFoundWithOrWithoutTheirVariationSelector() {
        val text = "Salut 🙂 et ❤️ puis ❤ et 😀"
        val found = Emoticons.find(text, shortcuts = false)
        assertEquals(listOf(0, at("Heart"), at("Heart")), found.map { it.index })
        assertEquals("🙂", text.substring(found[0].start, found[0].end))
        assertEquals("❤️", text.substring(found[1].start, found[1].end))
        assertEquals("❤", text.substring(found[2].start, found[2].end))
    }

    @Test fun typedShortcutsAreFoundOnlyBetweenSpacesAndTheLongestWins() {
        val found = Emoticons.find(":) bien :-) <3<3 =)) >:O fin :D.", shortcuts = true)
        assertEquals(listOf(0, 0, at("Love Struck"), at("ROTFL"), at("Angry"), at("Big Smile")), found.map { it.index })
        // Inside a word or an address: text.
        assertTrue(Emoticons.find("http://site a:)b (see B)", shortcuts = true).isEmpty())
        // The field keeps them as text (sim-149, C): only emojis without shortcuts.
        assertTrue(Emoticons.find(":) ;)", shortcuts = false).isEmpty())
    }
}
