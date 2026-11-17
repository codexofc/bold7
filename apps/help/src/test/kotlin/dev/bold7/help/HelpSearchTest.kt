package dev.bold7.help

import kotlin.test.Test
import kotlin.test.assertEquals

// Help's search over searchContent.txt: page|title|keywords (docs/BBOS7-VISUEL.md §39).
class HelpSearchTest {
    private val index = HelpSearch(
        """
        1000891.html|Delete a message
        1001316.html|View a full picture on a webpage|view,full,picture,web_page
        26116.html|Calculator|calculator
        1446616.html|How to: Keyboard|keyboard,how_to
        """.trimIndent() + "\r\n",
    )

    private fun titles(q: String) = index.find(q).map { it.title }

    @Test fun `every word typed starts a word of the title or a keyword`() {
        assertEquals(listOf("Delete a message"), titles("mess"))
        assertEquals(listOf("View a full picture on a webpage"), titles("web page"))
        assertEquals(listOf("How to: Keyboard"), titles("KEY how"))
        assertEquals(emptyList(), titles("xmessage"))
    }

    @Test fun `nothing typed finds nothing`() {
        assertEquals(emptyList(), titles("  "))
    }

    @Test fun `a result opens its page`() {
        assertEquals("26116.html", index.find("calc").single().page)
    }
}
