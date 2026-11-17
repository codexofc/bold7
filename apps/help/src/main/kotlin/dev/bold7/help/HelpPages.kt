package dev.bold7.help

// The pages of the 9900's help as Help shows them, pure (docs/BBOS7-VISUEL.md §39). The 9900's Help
// rewrites its pages while it shows them (processRimTag, the WLAN regular expression, theme://
// images); this does the same for the 9900: Wi-Fi supported, so wifiText's variants stay and the
// WLAN ones go.

object HelpPages {
    /** Features of `<RIM type="supports">` the 9900 has (A: the 9900's pages are the wifiText ones). */
    private val SUPPORTED = setOf("wifiText")
    private val RIM_TAG = Regex("""<RIM type="supports" (include|exclude)="([^"]*)">(.*?)</RIM>""", RegexOption.DOT_MATCHES_ALL)
    private val THEME = Regex("""theme://([A-Za-z0-9_]+)""")
    private val WLAN = Regex("WLAN|wlan|WLan|Wlan")
    private val TEXT = Regex(">([^<]+)<")
    private val VIEWPORT = Regex("""<meta name="viewport"[^>]*>""")

    /**
     * The trackpad's focus on a link, drawn as the 9900's browser draws the pointer over it (the
     * style sheet's :hover, A: style.css): the blue of OS 7's focus, white text.
     */
    private const val FOCUS = "<style>a:focus{outline:none;background:linear-gradient(#04B9F7,#0B88ED);color:#FFF}" +
        "a:focus *{color:#FFF}</style>"

    /**
     * [html] of the page [name] as Help shows it; the contents (index.html) end with Bold7's section.
     * [viewport], when given, replaces the page's own (target-densitydpi=device-dpi, which WebView
     * ignores) to show one CSS pixel per pixel of the screen, as the 9900's browser field did.
     */
    fun prepare(html: String, name: String, viewport: String? = null): String {
        var p = RIM_TAG.replace(html) { m ->
            val has = m.groupValues[2] in SUPPORTED
            if ((m.groupValues[1] == "include") == has) m.groupValues[3] else ""
        }
        p = THEME.replace(p) { "theme/${it.groupValues[1]}.png" }
        p = TEXT.replace(p) { m -> ">" + WLAN.replace(m.groupValues[1], "Wi-Fi") + "<" }
        val head = FOCUS + (viewport?.let { """<meta name="viewport" content="$it">""" } ?: "")
        if (viewport != null) p = VIEWPORT.replace(p, "")
        p = p.replaceFirst("</head>", "$head</head>").takeIf { it !== p } ?: (head + p)
        if (name == "index.html") {
            val end = p.lastIndexOf("</div>")
            if (end >= 0) p = p.substring(0, end) + BOLD7_SECTION + p.substring(end)
        }
        return p
    }

    /** Bold7's own topics (assets/bold7/), at the end of the contents, set apart by their heading. */
    private val BOLD7_SECTION = """
         <h1 class="topictitle1">Bold7</h1>
         <span><a href="bold7/about.html"><span>About Bold7 (not part of BlackBerry's help)</span></a></span>
         <span><a href="bold7/remove.html"><span>Remove Bold7</span></a></span>
         <span><a href="bold7/root.html"><span>Root and safe mode</span></a></span>
         <span><a href="bold7/updates.html"><span>Updates of your phone's system</span></a></span>
         <span><a href="bold7/setup.html"><span>Setup Wizard</span></a></span>
""".trimEnd() + "\n         "

    /** The contents when the user's file has no help Bold7 can read (the simulator's): Bold7's section alone. */
    fun contentsWithoutRim(): String =
        """<!DOCTYPE html><html><head><link rel="stylesheet" type="text/css" href="bold7/bold7.css"><title>Help</title>$FOCUS</head><body>""" +
            """<div class="intro">The help of the BlackBerry 9900 could not be read from your file.</div><div class="indexLinks">""" +
            BOLD7_SECTION + "</div></body></html>"

    /**
     * The entry of the contents an app's topic names (D: the 9900's Help maps its topics in code
     * Bold7 cannot read; each Bold7 app opens the category of the contents that is about it).
     */
    private val TOPICS = mapOf(
        "messages_index" to "Messages",
        "phone" to "Phone",
        "contacts" to "Contacts",
        "calendar" to "Calendar",
        "clock" to "Clock",
        "tasks" to "Tasks and memos",
        "memopad" to "Tasks and memos",
        "calculator" to "Calculator",
        "pictures" to "Media",
    )
    private val LINK = Regex("""<a href="([^"]+\.html)"><span[^>]*>\s*([^<]*?)\s*</span></a>""")

    /** The page of [topic] in the contents [index] (as the user's file has it), else the contents. */
    fun topicPage(index: String, topic: String?): String {
        val label = TOPICS[topic] ?: return "index.html"
        val shown = RIM_TAG.replace(index) { m -> if ((m.groupValues[1] == "include") == (m.groupValues[2] in SUPPORTED)) m.groupValues[3] else "" }
        return LINK.findAll(shown).firstOrNull { it.groupValues[2] == label }?.groupValues?.get(1) ?: "index.html"
    }
}
