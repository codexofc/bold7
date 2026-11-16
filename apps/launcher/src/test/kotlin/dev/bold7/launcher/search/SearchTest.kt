package dev.bold7.launcher.search

import dev.bold7.ui.Dir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchTest {
    // Word starts, as BlackBerry 10 highlights them on a capture of « s »:
    // « Post Facebook [s]tatus », « [S]end a BBM message », « Alexia [S]ánchez ».
    @Test fun `a query matches the start of any word`() {
        assertEquals(listOf(14..14), matchRanges("Post Facebook status", "s"))
        assertEquals(listOf(0..0), matchRanges("Send a BBM message", "s"))
        assertEquals(listOf(7..7), matchRanges("Alexia Sánchez", "s"))
        assertNull(matchRanges("Calculator", "lat")) // inside a word: no
    }

    @Test fun `case and accents are ignored, the text keeps its own`() {
        assertEquals(listOf(7..9), matchRanges("Alexia Sánchez", "SAN"))
        assertEquals(listOf(0..3), matchRanges("Météo", "mete"))
        assertEquals(listOf(0..2), matchRanges("Ecran", "écr"))
    }

    @Test fun `every word of the query must start a word, each its own`() {
        assertEquals(listOf(0..1, 7..8), matchRanges("Google Maps", "go ma"))
        assertEquals(listOf(0..1, 7..8), matchRanges("Google Maps", "ma go")) // in the text's order
        assertNull(matchRanges("Google Maps", "go mo"))
        assertNull(matchRanges("Play Store", "pl pl")) // one word cannot take two of the query
        assertEquals(listOf(0..1, 5..6), matchRanges("Play Plus", "pl pl"))
    }

    @Test fun `punctuation separates words, a blank query matches nothing`() {
        assertEquals(listOf(2..5), matchRanges("e-mail", "mail"))
        assertNull(matchRanges("Contacts", "  "))
    }

    @Test fun `a phone number typed is recognised`() {
        assertTrue(phoneLike("+1 555 12-34"))
        assertTrue(phoneLike("0612"))
        assertFalse(phoneLike("Bob"))
        assertFalse(phoneLike("12")) // too short to be meant
    }

    private fun hits(n: Int, what: String = "x") = (1..n).map { Hit("$what$it", "$what$it", emptyList()) }

    // sim-54: Dial, then one grid of tiles, the apps and a tile for each category with its count.
    @Test fun `the page is one grid, the apps then a tile for each other category`() {
        val web = Hit(WebSearch, "Extended Search", emptyList(), "x")
        val page = page(
            listOf(
                Section(Category.WEB, listOf(web)),
                Section(Category.CONTACTS, hits(2, "c"), total = 36),
                Section(Category.APPS, hits(5, "p")),
                Section(Category.MUSIC, emptyList()),
            ),
            filter = null,
            dial = "555",
        )
        val contacts = Hit(Category.CONTACTS, "", emptyList(), count = 36)
        assertEquals(
            listOf(
                Row.Item(Hit(Dial("555"), "555", emptyList()), 0),
                Row.Apps(hits(5, "p") + contacts, 1),
                Row.Item(web, 7),
            ),
            page.rows,
        )
        assertEquals(8, page.targets.size)
        assertEquals(contacts, page.targets[6])
    }

    @Test fun `more than six tiles wrap to a second row`() {
        val page = page(listOf(Section(Category.APPS, hits(8, "p"))), filter = null)
        assertEquals(listOf(Row.Apps(hits(6, "p"), 0), Row.Apps(hits(8, "p").drop(6), 6)), page.rows)
    }

    @Test fun `a category taken shows its results alone, as a list`() {
        val page = page(listOf(Section(Category.APPS, hits(3, "p")), Section(Category.CONTACTS, hits(3, "c"), total = 40)), filter = Category.CONTACTS)
        assertEquals(listOf(Row.Header(Category.CONTACTS, 40)) + hits(3, "c").mapIndexed { i, h -> Row.Item(h, i) }, page.rows)
        assertEquals(Page(emptyList(), emptyList()), page(listOf(Section(Category.CONTACTS, emptyList())), filter = Category.CONTACTS))
    }

    // Targets: 0 the dial, 1 to 7 the tiles (6 then 1), 8 the web.
    @Test fun `the trackpad walks the targets, by column in the grid`() {
        val web = Hit(WebSearch, "w", emptyList())
        val page = page(listOf(Section(Category.APPS, hits(7, "p")), Section(Category.WEB, listOf(web))), filter = null, dial = "555")
        assertEquals(1, step(page, 0, Dir.DOWN))
        assertEquals(0, step(page, 3, Dir.UP)) // from a tile of the first row, the dial above
        assertEquals(7, step(page, 3, Dir.DOWN)) // the same column, or the last of a shorter row
        assertEquals(1, step(page, 7, Dir.UP))
        assertEquals(8, step(page, 7, Dir.DOWN)) // the web's row
        assertEquals(2, step(page, 1, Dir.RIGHT))
        assertEquals(6, step(page, 6, Dir.RIGHT)) // the row ends there
        assertEquals(1, step(page, 1, Dir.LEFT))
        assertEquals(0, step(page, 0, Dir.RIGHT)) // a row has nothing beside it
        assertEquals(8, step(page, 8, Dir.DOWN)) // the end
    }

    @Test fun `labels folded once match as the labels themselves`() {
        val labels = listOf("Météo", "Google Maps", "Calculatrice")
        val folded = labels.map(::Folded)
        assertEquals(hits(labels, "ma") { it }, hits(labels, folded, "ma"))
        assertEquals(listOf(0..3), matchRanges(Folded("Météo"), "mete"))
    }

    // Contacts arrive after the frame of the key: the focus stays on what it was on.
    @Test fun `the focus keeps its target when results come in before it`() {
        val tile = Hit(Category.CONTACTS, "", emptyList(), count = 2)
        val before = page(listOf(Section(Category.APPS, hits(1, "p")), Section(Category.SETTINGS, hits(1, "s"))), filter = null)
        val after = page(listOf(Section(Category.APPS, hits(1, "p")), Section(Category.CONTACTS, hits(2, "c")), Section(Category.SETTINGS, hits(1, "s"))), filter = null)
        assertEquals(1, before.targets.size - 1)
        assertEquals(after.targets.indexOf(before.targets[1]), reselect(after, before.targets[1]))
        assertEquals(1, after.targets.indexOf(tile))
        assertEquals(0, reselect(after, hits(1, "gone")[0])) // gone: the top
        assertEquals(0, reselect(after, null))
    }

    // A request to the network is chosen, never taken by Enter on the soft focus.
    @Test fun `enter on the soft focus never searches the web`() {
        val web = Hit(WebSearch, "Extended Search", emptyList(), "calc")
        val only = page(listOf(Section(Category.WEB, listOf(web))), filter = null)
        assertNull(entered(only, 0, moved = false))
        assertEquals(web, entered(only, 0, moved = true))
        val app = hits(1, "p")[0]
        assertEquals(app, entered(page(listOf(Section(Category.APPS, listOf(app)), Section(Category.WEB, listOf(web))), filter = null), 0, moved = false))
        assertNull(entered(Page(emptyList(), emptyList()), 0, moved = true))
    }

    @Test fun `the tiles of the categories keep their order, whatever order the sources answer in`() {
        val page = page(
            listOf(
                Section(Category.TEXT_MESSAGES, hits(1, "t")),
                Section(Category.PICTURES, hits(1, "i")),
                Section(Category.CALENDAR, hits(1, "a")),
                Section(Category.CONTACTS, hits(1, "c")),
            ),
            filter = null,
        )
        assertEquals(
            listOf(Category.CONTACTS, Category.PICTURES, Category.CALENDAR, Category.TEXT_MESSAGES),
            page.targets.map { it.item },
        )
    }

    // search.mo: « Agenda, Datebook, Date book, Diary, Schedule ».
    @Test fun `an app is found by its keywords, its name not highlighted`() {
        val apps = listOf("Agenda Google", "Calendrier", "Horloge")
        val keywords = listOf(emptyList(), APP_KEYWORDS.getValue("bbos7_calendar"), APP_KEYWORDS.getValue("bbos7_clock")).map { k -> k.map(::Folded) }
        val found = hits(apps, apps.map(::Folded), "agenda", keywords::get)
        assertEquals(listOf("Agenda Google", "Calendrier"), found.map { it.title })
        assertEquals(listOf(0..5), found[0].ranges)
        assertEquals(emptyList(), found[1].ranges)
        assertEquals(listOf("Horloge"), hits(apps, apps.map(::Folded), "chrono", keywords::get).map { it.title })
    }

    // Chrome is « Browser » on the grid, « chrome » still finds it, not highlighted.
    @Test fun `a renamed app is found by its Android name too`() {
        val apps = listOf("Browser", "Remember")
        val aliases = listOf(appAliases("bbos7_browser", "Chrome"), appAliases("bbos7_memopad", null)).map { k -> k.map(::Folded) }
        val found = hits(apps, apps.map(::Folded), "chrome", aliases::get)
        assertEquals(listOf("Browser"), found.map { it.title })
        assertEquals(emptyList(), found.single().ranges)
        assertEquals(listOf("Browser"), hits(apps, apps.map(::Folded), "www", aliases::get).map { it.title })
        assertEquals(listOf("Internet", "WWW", "Chrome"), appAliases("bbos7_browser", "Chrome"))
        assertEquals(listOf("Keep Notes"), appAliases(null, "Keep Notes"))
    }

    @Test fun `a provider narrows on the longest word, its wildcards escaped`() {
        assertEquals("facture", narrowing("la facture 3"))
        assertNull(narrowing(" - "))
        assertEquals("%50\\%\\_a\\\\b%", like("50%_a\\b"))
    }

    @Test fun `a number is found by digits anywhere in it`() {
        assertTrue(numberMatches("+33 6 12 34 56 78", "345"))
        assertTrue(numberMatches("0612345678", "06 12"))
        assertFalse(numberMatches("0612345678", "12")) // too short
        assertFalse(numberMatches("0612345678", "anne"))
        assertFalse(numberMatches("0612345678", "999"))
    }

    // Upcoming first, soonest first, then the past, latest first; a repeated event once.
    @Test fun `the agenda shows what comes before what went`() {
        data class E(val id: Int, val begin: Long)
        val events = listOf(E(1, 50), E(2, 200), E(3, 120), E(4, 10), E(2, 900), E(5, 100))
        assertEquals(listOf(5, 3, 2, 1, 4), upcomingFirst(events, now = 100, begin = E::begin, id = E::id).map { it.id })
    }

    // MediaProvider's strict grammar refuses COLLATE LOCALIZED ("Invalid token LOCALIZED", Q25,
    // content query on audio/media): Music was never read.
    @Test fun `the music is sorted in words MediaStore accepts`() {
        assertEquals("title COLLATE NOCASE", MUSIC_ORDER)
        assertFalse("LOCALIZED" in MUSIC_ORDER)
    }

    // A provider's error is logged with its message when it names nothing typed.
    @Test fun `an error message is logged only if it holds nothing searched`() {
        assertEquals("Invalid token LOCALIZED", loggable("Invalid token LOCALIZED", "calc"))
        assertNull(loggable("Unknown URL content://com.android.calendar/instances/search/1/2/Anne", "anne"))
        assertNull(loggable("no such row 0612345678", "0612"))
        assertNull(loggable(null, "calc"))
        assertEquals(200, loggable("x".repeat(500), "calc")?.length)
    }
}

