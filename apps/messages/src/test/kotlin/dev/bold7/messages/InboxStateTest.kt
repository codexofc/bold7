package dev.bold7.messages

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboxStateTest {
    // 0 day 12, 1 a, 2 b, 3 day 11, 4 c
    private val rows = inboxLines(snapshot(sms("a", 12, 9), sms("b", 12, 8), sms("c", 11, 9)), Filter.ALL, null, format)
    private val shown = InboxState().reduce(InboxEvent.Shown(rows))

    @Test fun theFirstReadShowsTheListFocusedOnItsTop() {
        assertEquals(false, InboxState().loaded)
        assertEquals(true, shown.loaded)
        assertEquals(0, shown.focus)
        assertEquals(rows[0], shown.focusedLine)
    }

    @Test fun theTrackpadStepsAndStopsAtTheEnds() {
        assertEquals(1, shown.reduce(InboxEvent.Step(down = true)).focus)
        assertEquals(0, shown.reduce(InboxEvent.Step(down = false)).focus)
        val last = shown.reduce(InboxEvent.Moved(4))
        assertEquals(4, last.reduce(InboxEvent.Step(down = true)).focus)
        assertEquals(2, shown.reduce(InboxEvent.Moved(2)).focus)
        assertEquals(4, shown.reduce(InboxEvent.Moved(9)).focus)
    }

    @Test fun theFocusStaysOnItsLineWhenTheListChanges() {
        val onB = shown.reduce(InboxEvent.Moved(2))
        val newer = inboxLines(snapshot(sms("n", 12, 10), sms("a", 12, 9), sms("b", 12, 8), sms("c", 11, 9)), Filter.ALL, null, format)
        assertEquals(3, onB.reduce(InboxEvent.Shown(newer)).focus)
        // Its line gone: the same place, within the list.
        val without = inboxLines(snapshot(sms("a", 12, 9)), Filter.ALL, null, format)
        assertEquals(1, onB.reduce(InboxEvent.Shown(without)).focus)
        assertEquals(0, onB.reduce(InboxEvent.Shown(emptyList())).focus)
        assertNull(onB.reduce(InboxEvent.Shown(emptyList())).focusedLine)
    }

    @Test fun searchOpensItsFieldWithTheFocusAndTheTrackpadLeavesIt() {
        val searching = shown.reduce(InboxEvent.Moved(2)).reduce(InboxEvent.SearchOpened)
        assertEquals("", searching.search)
        assertEquals(FIELD, searching.focus)
        assertNull(searching.focusedLine)
        assertEquals("al", searching.reduce(InboxEvent.Typed("al")).search)
        assertEquals(0, searching.reduce(InboxEvent.Step(down = true)).focus)
        assertEquals(FIELD, searching.reduce(InboxEvent.Step(down = true)).reduce(InboxEvent.Step(down = false)).focus)
        // Without the field, up from the top stays there.
        assertEquals(0, shown.reduce(InboxEvent.Step(down = false)).focus)
    }

    @Test fun viewFolderListsTheViewsAndChoosingOneShowsIt() {
        val folders = shown.reduce(InboxEvent.FoldersOpened)
        assertEquals(Screen.FOLDERS, folders.screen)
        assertEquals(FOLDER_VIEWS.indexOf(Filter.ALL), folders.folder)
        assertEquals(1, folders.reduce(InboxEvent.FolderStep(down = true)).folder)
        assertEquals(0, folders.reduce(InboxEvent.FolderStep(down = false)).folder)
        assertEquals(FOLDER_VIEWS.lastIndex, folders.reduce(InboxEvent.Filtered(Filter.CALLS)).reduce(InboxEvent.FoldersOpened).let {
            var s = it
            repeat(20) { s = s.reduce(InboxEvent.FolderStep(down = true)) }
            s.folder
        })
        val calls = folders.reduce(InboxEvent.Filtered(Filter.CALLS))
        assertEquals(Screen.LIST, calls.screen)
        assertEquals(Filter.CALLS, calls.filter)
        assertEquals(0, calls.focus)
        assertEquals(FOLDER_VIEWS.indexOf(Filter.CALLS), calls.reduce(InboxEvent.FoldersOpened).folder)
    }

    @Test fun choosingAViewClosesTheSearch() {
        assertNull(shown.reduce(InboxEvent.SearchOpened).reduce(InboxEvent.Filtered(Filter.TEXT)).search)
    }

    @Test fun escapeUndoesOneThingAtATimeThenLeaves() {
        // "View all your messages": Escape (guide 7.1 p. 52, B).
        val deep = shown.reduce(InboxEvent.Filtered(Filter.TEXT)).reduce(InboxEvent.SearchOpened).reduce(InboxEvent.Typed("a")).reduce(InboxEvent.FoldersOpened)
        val s1 = deep.back()!!
        assertEquals(Screen.LIST, s1.screen)
        assertEquals("a", s1.search)
        val s2 = s1.back()!!
        assertNull(s2.search)
        assertEquals(Filter.TEXT, s2.filter)
        val s3 = s2.back()!!
        assertEquals(Filter.ALL, s3.filter)
        assertEquals(0, s3.focus)
        assertNull(s3.back())
    }

    @Test fun theSearchEditorTakesWhatTypesTheListTheRest() {
        for (k in listOf(FieldKey.TEXT, FieldKey.DELETE, FieldKey.LEFT, FieldKey.RIGHT)) assertTrue(searchTakes(k), "$k")
        for (k in listOf(FieldKey.UP, FieldKey.DOWN, FieldKey.CLICK, FieldKey.BACK, FieldKey.MENU)) assertFalse(searchTakes(k), "$k")
    }
}

class TextMessagesEntryTest {
    private val rows = inboxLines(snapshot(sms("a", 12, 9), call("c", 12, 8)), Filter.ALL, null, format)

    // The alias the manifest declares and the tables of core/icons name and draw.
    @Test fun theAliasIsTheOneTheManifestAndTheTablesName() {
        assertEquals(Filter.TEXT, entryView(TEXT_MESSAGES_ALIAS))
        assertEquals(Filter.ALL, entryView("dev.bold7.messages.MessagesActivity"))
        assertEquals(Filter.ALL, entryView(null))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android:name=\"." + TEXT_MESSAGES_ALIAS.substringAfterLast('.') + "\"" in manifest)
        val raw = "../../core/icons/src/main/res/raw"
        assertTrue("dev.bold7.messages\t$TEXT_MESSAGES_ALIAS\tText Messages\tshown" in File("$raw/bbos7_apps.tsv").readText())
        assertTrue("dev.bold7.messages\t$TEXT_MESSAGES_ALIAS\tbbos7_text_messages\t" in File("$raw/bbos7_icons.tsv").readText())
    }

    // View of the Phone's missed calls dialog opens the missed calls (sim-204, C).
    @Test fun theMissedCallsDialogOpensTheCallsView() {
        assertEquals(Filter.CALLS, entryView("dev.bold7.messages.MessagesActivity", MISSED_CALLS_VIEW))
        assertEquals(Filter.TEXT, entryView(TEXT_MESSAGES_ALIAS, null))
        assertEquals(Filter.ALL, entryView(null, "anything else"))
    }

    // the Text Messages icon opens the same list on its texts (sim-53, C).
    @Test fun theTextMessagesIconOpensTheTextsView() {
        val s = InboxState().reduce(InboxEvent.Opened(Filter.TEXT))
        assertEquals(Filter.TEXT, s.filter)
        assertEquals(Filter.TEXT, s.base)
        assertEquals(InboxState(), InboxState().reduce(InboxEvent.Opened(Filter.ALL)))
    }

    // Escape goes back to the view the icon opened, then leaves to the home (D: two icons, two
    // screens of their own on the 9900); never to the unified inbox.
    @Test fun escapeEndsOnTheViewTheIconOpened() {
        val calls = InboxState().reduce(InboxEvent.Opened(Filter.TEXT)).reduce(InboxEvent.Shown(rows)).reduce(InboxEvent.Filtered(Filter.CALLS))
        val back = calls.back()!!
        assertEquals(Filter.TEXT, back.filter)
        assertNull(back.back())
    }

    // The other icon, the app already open: its own view, from the top; the same icon again: as left.
    @Test fun theOtherIconStartsOverOnItsViewAndTheSameOneKeepsWhereItWas() {
        val searching = InboxState().reduce(InboxEvent.Opened(Filter.TEXT)).reduce(InboxEvent.Shown(rows)).reduce(InboxEvent.SearchOpened)
        assertEquals(searching, searching.reduce(InboxEvent.Opened(Filter.TEXT)))
        val all = searching.reduce(InboxEvent.Opened(Filter.ALL))
        assertEquals(Filter.ALL, all.filter)
        assertEquals(Filter.ALL, all.base)
        assertNull(all.search)
        assertEquals(0, all.focus)
        assertEquals(Screen.LIST, all.screen)
    }

    // an account's icon (a shortcut of Messages) opens its view; Escape leaves for
    // the home from there (D), the views of View Folder stay within the account.
    @Test fun anAccountsIconOpensItsViewAndKeepsIt() {
        val s = InboxState().reduce(InboxEvent.Opened(Filter.ALL, account = "mail-1"))
        assertEquals("mail-1", s.account)
        val calls = s.reduce(InboxEvent.Shown(rows)).reduce(InboxEvent.Filtered(Filter.UNREAD))
        assertEquals("mail-1", calls.account)
        assertEquals("mail-1", calls.back()!!.account)
        assertNull(calls.back()!!.back())
        // Messages' own icon afterwards: everything again.
        assertNull(s.reduce(InboxEvent.Opened(Filter.ALL)).account)
        assertEquals(s, s.reduce(InboxEvent.Opened(Filter.ALL, account = "mail-1")))
    }
}
