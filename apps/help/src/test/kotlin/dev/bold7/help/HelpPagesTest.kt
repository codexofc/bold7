package dev.bold7.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// What Help does to a page of the 9900 before showing it (docs/BBOS7-VISUEL.md §39).
class HelpPagesTest {
    private val wifi = """<span><RIM type="supports" include="wifiText"><a href="1.html">Wi-Fi</a></RIM></span>""" +
        """<span><RIM type="supports" exclude="wifiText"><a href="2.html">WLAN</a></RIM></span>"""

    @Test fun `the 9900 supports wifiText, its WLAN variants go`() {
        val p = HelpPages.prepare(wifi, "x.html")
        assertTrue("""<a href="1.html">Wi-Fi</a>""" in p)
        assertFalse("2.html" in p)
        assertFalse("RIM" in p)
    }

    @Test fun `WLAN is written Wi-Fi in the text, not in the markup`() {
        val p = HelpPages.prepare("""<p title="WLAN">Turn on WLAN or wlan</p>""", "x.html")
        assertTrue("""title="WLAN"""" in p)
        assertTrue("Turn on Wi-Fi or Wi-Fi" in p)
    }

    @Test fun `theme images are the theme's files beside the pages`() {
        val p = HelpPages.prepare("""<span style="background:no-repeat url(&#34;theme://cat_app_messages&#34;)">Messages</span>""", "x.html")
        assertTrue("url(&#34;theme/cat_app_messages.png&#34;)" in p)
    }

    @Test fun `the trackpad's focus is drawn as the 9900's hover`() {
        val p = HelpPages.prepare("<html><head><title>t</title></head><body></body></html>", "x.html")
        assertTrue(p.indexOf(":focus") in p.indexOf("<head>")..p.indexOf("</head>"))
    }

    @Test fun `the screen's viewport replaces the page's`() {
        val p = HelpPages.prepare("""<html><head><meta name="viewport" content="initial-scale=1.0, target-densitydpi=device-dpi"></head></html>""", "x.html", "width=720")
        assertFalse("target-densitydpi" in p)
        assertTrue("""<meta name="viewport" content="width=720">""" in p)
    }

    private val index = """<html><head></head><body><div class="indexLinks"><h1 class="topictitle1">User Guide</h1>
        <span><a href="292735.html"><span style="x">Messages</span></a></span>
        <span><RIM type="supports" exclude="wifiText"><a href="9.html"><span>Tasks and memos</span></a></RIM></span>
        <span><a href="1102526.html"><span>Tasks and memos</span></a></span>
        </div></body></html>"""

    @Test fun `Bold7's section ends the contents, after RIM's`() {
        val p = HelpPages.prepare(index, "index.html")
        val ours = p.indexOf(">Bold7<")
        assertTrue(ours > p.indexOf("292735.html"))
        assertTrue(ours < p.lastIndexOf("</div>"))
        assertTrue("bold7/remove.html" in p)
        assertFalse("bold7/remove.html" in HelpPages.prepare(index, "1.html"))
    }

    @Test fun `an app's topic is its entry of the contents`() {
        assertEquals("292735.html", HelpPages.topicPage(index, "messages_index"))
        assertEquals("1102526.html", HelpPages.topicPage(index, "memopad"))
        assertEquals("index.html", HelpPages.topicPage(index, "nothing"))
        assertEquals("index.html", HelpPages.topicPage(index, null))
    }

    @Test fun `without RIM's help the contents are Bold7's section alone`() {
        val p = HelpPages.contentsWithoutRim()
        assertTrue(">Bold7<" in p && "bold7/remove.html" in p)
    }
}
