package dev.bold7.mail

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlTest {
    @Test
    fun `tags go, blocks and breaks become lines, source line breaks are spaces`() {
        val html = "<html><head><title>T</title><style>p { color: red }</style></head><body>\n" +
            "<p>Bonjour\n  Alice,</p><div>Le rendez-vous<br>est <b>lundi</b>.</div>\n<ul><li>un</li><li>deux</li></ul></body></html>"
        assertEquals("Bonjour Alice,\nLe rendez-vous\nest lundi.\nun\ndeux", htmlToText(html))
    }

    @Test
    fun `scripts and comments never show`() {
        assertEquals("a b", htmlToText("a <script type=\"x\">alert(1)</script><!-- hidden <p>x</p> -->b"))
    }

    @Test
    fun `entities are decoded, named and numeric`() {
        assertEquals("Café & thé < 3 € « oui » l’été…", htmlToText("Caf&eacute; &amp; th&#233; &lt; 3 &euro; &laquo;&nbsp;oui&nbsp;&raquo; l&rsquo;&#xE9;t&eacute;&hellip;"))
        assertEquals("&unknown; stays", htmlToText("&unknown; stays"))
    }

    @Test
    fun `blank lines are kept to one`() {
        assertEquals("a\n\nb", htmlToText("<p>a</p><p></p><p></p><br><br><p>b</p>"))
    }

    @Test
    fun `a link keeps its text only, no image is fetched`() {
        assertEquals("Voir le site", htmlToText("<a href=\"https://tracker.example/x\">Voir le site</a><img src=\"https://tracker.example/pixel.gif\">"))
    }
}
