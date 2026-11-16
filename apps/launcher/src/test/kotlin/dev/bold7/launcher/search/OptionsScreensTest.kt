package dev.bold7.launcher.search

import dev.bold7.launcher.options.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptionsScreensTest {
    @Test
    fun `the search opens each Options screen once, never the root, with words that lead to it`() {
        val pages = optionsScreens()
        assertTrue(pages.all { it.screen != null && it.screen != Screen.MAIN && it.keywords.isNotEmpty() && it.action.isEmpty() })
        assertEquals(pages.size, pages.map { it.screen }.distinct().size)
        assertTrue(pages.any { it.label == "Date and Time" && it.screen == Screen.DATE_TIME })
    }
}
