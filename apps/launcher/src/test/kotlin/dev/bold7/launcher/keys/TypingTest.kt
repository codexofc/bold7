package dev.bold7.launcher.keys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TypingTest {
    private fun run(mode: LaunchByTyping, vararg events: TypingEvent): List<TypingEffect> {
        var state = Typing()
        val effects = mutableListOf<TypingEffect>()
        for (e in events) {
            val (next, effect) = type(state, mode, e)
            state = next
            if (effect != null) effects += effect
        }
        return effects
    }

    private val search = LaunchByTyping.UNIVERSAL_SEARCH
    private val shortcuts = LaunchByTyping.APPLICATION_SHORTCUTS

    @Test fun `in universal search every letter searches, shortcut letters included`() {
        assertEquals(listOf(TypingEffect.Search("c")), run(search, TypingEvent.Down('c')))
        assertEquals(listOf(TypingEffect.Search("e")), run(search, TypingEvent.Down('e')))
    }

    @Test fun `the letters typed before the search shows are kept`() {
        var state = Typing()
        for (c in "ex") state = type(state, search, TypingEvent.Down(c)).first
        assertEquals("ex", state.text)
        assertNull(type(state, search, TypingEvent.Down('c')).second) // already searching: the field takes it
    }

    @Test fun `in application shortcuts a letter opens its app and searches nothing`() {
        assertEquals(listOf(TypingEffect.Open('c')), run(shortcuts, TypingEvent.Down('c')))
        assertEquals(listOf(TypingEffect.Open('c'), TypingEffect.Open('x')), run(shortcuts, TypingEvent.Down('c'), TypingEvent.Down('x')))
    }

    @Test fun `in application shortcuts H opens Help, in universal search it searches`() {
        assertEquals(listOf(TypingEffect.Help, TypingEffect.Help), run(shortcuts, TypingEvent.Down('h'), TypingEvent.Down('H')))
        assertEquals(listOf(TypingEffect.Search("h")), run(search, TypingEvent.Down('h')))
    }

    @Test fun `a reset forgets the letters`() {
        assertEquals(listOf(TypingEffect.Search("c"), TypingEffect.Search("e")), run(search, TypingEvent.Down('c'), TypingEvent.Reset, TypingEvent.Down('e')))
    }

    @Test fun `the setting is read back, search when absent or unknown`() {
        assertEquals(shortcuts, parseLaunchByTyping("APPLICATION_SHORTCUTS\n"))
        assertEquals(search, parseLaunchByTyping(null))
        assertEquals(search, parseLaunchByTyping("nothing"))
    }

    // D: the guide describes no letter, a letter opens the first app of the grid whose name starts with it.
    @Test fun `a letter opens the first app whose name starts with it, case and accents aside`() {
        val labels = listOf("Calendar", "Clock", "Éditeur", "Messages")
        assertEquals(0, appForLetter('c', labels))
        assertEquals(2, appForLetter('e', labels))
        assertEquals(3, appForLetter('M', labels))
        assertNull(appForLetter('z', labels))
        assertNull(appForLetter('1', labels))
    }

    @Test fun `M opens Messages even with MemoPad before it (sim-288)`() {
        assertEquals(2, appForLetter('m', listOf("Media", "MemoPad", "Messages")))
        assertEquals(0, appForLetter('m', listOf("Media", "MemoPad")))
    }
}
