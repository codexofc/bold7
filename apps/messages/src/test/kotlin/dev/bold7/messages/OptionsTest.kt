package dev.bold7.messages

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The options of Messages and the menu entries that use them.
class OptionsTest {
    private fun keys(s: Snapshot, filter: Filter = Filter.ALL, search: String? = null, account: String? = null, searchIn: SearchIn = SearchIn.ANY) =
        inboxLines(s, filter, search, format, account, searchIn).filterIsInstance<ItemLine>().map { it.item.sourceKey }

    private fun snap(options: MessagesOptions = MessagesOptions(), saved: Set<String> = emptySet(), items: Array<HubItem>) =
        snapshot(*items).copy(options = options, saved = saved)

    private fun mail(key: String, day: Int, hour: Int, sender: String = "Dan", subject: String = "Plan", box: Box = Box.INBOX, account: String = "mail:1") =
        HubItem(account, key, ItemKind.EMAIL, at(day, hour), sender, address = "$sender@x.org", subject = subject, preview = "body", conversationId = key, box = box)

    // Hide Sent Messages (guide 7.1 p. 87, B)

    @Test fun hideSentLeavesTheSentMessagesOutOfTheListsButNotOfTheOutgoingView() {
        val items = arrayOf(sms("a", 12, 9, thread = "t"), sms("b", 12, 10, thread = "t", box = Box.SENT), sms("c", 11, 9, box = Box.SENT, thread = "u"))
        val s = snap(MessagesOptions(hideSent = true), items = items)
        assertEquals(listOf("a"), keys(s)) // the conversation falls back on its latest received message
        assertEquals(listOf("a"), keys(s, Filter.TEXT))
        assertEquals(listOf("b", "c"), keys(s, Filter.OUTGOING))
        assertEquals(listOf("b", "c"), keys(snap(items = items)))
    }

    // Inbox Management (guide p. 87, B)

    @Test fun anAccountHiddenInInboxManagementShowsOnlyFromItsOwnIcon() {
        val s = snap(MessagesOptions(hiddenAccounts = setOf("mail:1")), items = arrayOf(mail("m", 12, 9), sms("a", 12, 8)))
        assertEquals(listOf("a"), keys(s))
        assertEquals(listOf("m"), keys(s, account = "mail:1"))
    }

    @Test fun textMessagesHiddenLeaveTheInboxButStayInTheirOwnView() {
        val s = snap(MessagesOptions(showTexts = false), items = arrayOf(mail("m", 12, 9), sms("a", 12, 8)))
        assertEquals(listOf("m"), keys(s))
        assertEquals(listOf("a"), keys(s, Filter.TEXT))
    }

    // Display Style > Group by Subject, Single Messages (guide p. 88, B)

    @Test fun singleMessagesGivesEachMessageItsRowAndItsOwnId() {
        val items = arrayOf(sms("a", 12, 9, thread = "t"), sms("b", 12, 10, thread = "t"))
        assertEquals(listOf("b"), keys(snap(items = items)))
        val single = inboxLines(snap(MessagesOptions(group = false), items = items), Filter.ALL, null, format).filterIsInstance<ItemLine>()
        assertEquals(listOf("b", "a"), single.map { it.item.sourceKey })
        assertEquals(2, single.map { it.id }.toSet().size)
    }

    @Test fun readingOrDeletingASingleMessageTouchesOnlyIt() {
        val s = snap(MessagesOptions(group = false), items = arrayOf(sms("a", 12, 9, thread = "t"), sms("b", 12, 10, thread = "t")))
        val row = inboxLines(s, Filter.ALL, null, format).filterIsInstance<ItemLine>().first()
        assertEquals(listOf("b"), writeKeys(Request(Act.DELETE, row.item), s, Filter.ALL, null, paris).map { it.sourceKey })
        val grouped = snap(items = arrayOf(sms("a", 12, 9, thread = "t"), sms("b", 12, 10, thread = "t")))
        assertEquals(setOf("a", "b"), writeKeys(Request(Act.DELETE, row.item), grouped, Filter.ALL, null, paris).map { it.sourceKey }.toSet())
    }

    // View Saved Messages, Save (guide p. 91-93, B)

    @Test fun savedMessagesShowInTheirViewEvenOnceDeletedFromTheInbox() {
        val a = sms("a", 12, 9)
        val b = sms("b", 12, 8).copy(deleted = true)
        val c = sms("c", 12, 7)
        val s = snap(saved = setOf(a.savedKey(), b.savedKey()), items = arrayOf(a, b, c))
        assertEquals(listOf("a", "b"), keys(s, Filter.SAVED))
        assertEquals(listOf("a", "c"), keys(s))
        val line = inboxLines(s, Filter.ALL, null, format).filterIsInstance<ItemLine>()
        assertEquals(listOf(true, false), line.map { it.saved })
    }

    @Test fun savedViewIsNotOneOfViewFolderViews() {
        assertFalse(Filter.SAVED in FOLDER_VIEWS)
        assertEquals(Filter.entries.size - 1, FOLDER_VIEWS.size)
    }

    // Search by (guide p. 86, B)

    @Test fun searchBySenderRecipientAndSubjectLookAtOneFieldOfTheRightBox() {
        val s = snap(items = arrayOf(
            mail("m1", 12, 9, sender = "Dan", subject = "Plan"), mail("m2", 12, 8, sender = "Eve", subject = "Dan's plan"),
            mail("m3", 12, 7, sender = "Dan", subject = "Re", box = Box.SENT),
        ))
        assertEquals(listOf("m1"), keys(s, search = "Dan", searchIn = SearchIn.SENDER))
        assertEquals(listOf("m3"), keys(s, search = "Dan", searchIn = SearchIn.RECIPIENT))
        assertEquals(listOf("m2"), keys(s, search = "Dan", searchIn = SearchIn.SUBJECT))
        assertEquals(listOf("m1", "m2", "m3"), keys(s, search = "Dan"))
    }

    @Test fun searchByEntriesFollowWhatTheHighlightedMessageHas() {
        val s = snap(items = arrayOf(mail("m1", 12, 9), mail("m3", 12, 8, box = Box.SENT), sms("a", 12, 7)))
        val rows = inboxLines(s, Filter.ALL, null, format)
        fun by(i: Int) = searchBy(rows[i]).map { it.first }
        assertEquals(listOf(Act.SEARCH_SENDER, Act.SEARCH_SUBJECT), by(1))
        assertEquals(listOf(Act.SEARCH_RECIPIENT, Act.SEARCH_SUBJECT), by(2))
        assertEquals(listOf(Act.SEARCH_SENDER), by(3))
        assertEquals(listOf(Act.SEARCH_SENDER, Act.SEARCH_SUBJECT), by(0)) // a date field: the fields, no text
        assertEquals("", searchBy(rows[0]).first().second)
        assertEquals("Dan", searchBy(rows[1]).first().second)
        assertEquals("Plan", searchBy(rows[1]).last().second)
    }

    // The full menu of OS 7 (sim-44, sim-45, sim-289, sim-290, C)

    @Test fun theFullMenuHasOS7sRowsInOS7sOrderAndOnlyThoseWithAnEffect() {
        val row = inboxLines(snap(items = arrayOf(mail("m1", 12, 9))), Filter.ALL, null, format)[1]
        val menu = fullMenu(row)
        assertEquals(
            listOf(
                Act.OPEN, Act.MARK_READ, Act.SAVE, Act.DELETE, Act.COMPOSE_EMAIL, Act.COMPOSE_OTHER, Act.SEARCH, Act.SEARCH_BY, Act.DISPLAY_STYLE,
                Act.VIEW_FOLDER, Act.VIEW_SAVED, Act.NOTIFIERS, Act.OPTIONS,
            ),
            menu.map { it.action },
        )
        assertEquals(setOf(Act.COMPOSE_OTHER, Act.SEARCH_BY, Act.DISPLAY_STYLE), menu.filter { it.submenu }.map { it.action }.toSet())
        assertEquals(setOf(Act.COMPOSE_EMAIL, Act.SEARCH, Act.NOTIFIERS), menu.filter { it.separatorAbove }.map { it.action }.toSet())
        assertEquals(listOf(Act.OPEN, Act.MARK_READ, Act.DELETE, Act.COMPOSE_EMAIL, Act.COMPOSE, Act.SEARCH, Act.VIEW_FOLDER, Act.OPTIONS), shortMenu(row).map { it.action })
    }

    @Test fun saveIsOfferedOnlyOnAMailOrATextNotYetSaved() {
        val items = arrayOf(mail("m1", 12, 9), sms("a", 12, 8), call("c", 12, 7), note("n", 12, 6))
        val s = snap(saved = setOf(sms("a", 12, 8).savedKey()), items = items)
        val rows = inboxLines(s, Filter.ALL, null, format).filterIsInstance<ItemLine>()
        fun has(i: Int) = Act.SAVE in fullMenu(rows[i]).map { it.action }
        assertTrue(has(0)); assertFalse(has(1)); assertFalse(has(2)); assertFalse(has(3))
    }

    @Test fun inTheSavedViewDeletePriorIsNotOffered() {
        val a = sms("a", 12, 9)
        val rows = inboxLines(snap(saved = setOf(a.savedKey()), items = arrayOf(a)), Filter.SAVED, null, format)
        assertFalse(Act.DELETE_PRIOR in fullMenu(rows[0], Filter.SAVED).map { it.action })
        assertTrue(Act.DELETE in fullMenu(rows[1], Filter.SAVED).map { it.action })
    }

    // The state

    @Test fun searchByOpensTheResultsOnTheFirstLineAndEscapeClearsIt() {
        val base = InboxState().reduce(InboxEvent.Shown(emptyList()))
        val by = base.reduce(InboxEvent.SearchedBy(SearchIn.SENDER, "Dan"))
        assertEquals("Dan", by.search); assertEquals(SearchIn.SENDER, by.searchIn); assertEquals(0, by.focus)
        assertEquals(SearchIn.ANY, by.back()!!.searchIn)
        assertEquals(null, by.back()!!.search)
        assertEquals(SearchIn.ANY, by.reduce(InboxEvent.Filtered(Filter.TEXT)).searchIn)
    }
}

class OptionsFormsTest {
    private val accounts = listOf("mail:1", "mail:2")

    @Test fun messageDisplayAndActionsFlipsItsThreeChecks() {
        var o = MessagesOptions()
        assertEquals(listOf(false, true, true), o.displayChecks().map { it.second })
        o = o.toggled(OptionsScreen.DISPLAY, 0, accounts).toggled(OptionsScreen.DISPLAY, 1, accounts)
        assertEquals(listOf(true, false, true), o.displayChecks().map { it.second })
        assertEquals(o, o.toggled(OptionsScreen.DISPLAY, 3, accounts))
    }

    @Test fun inboxManagementHasOneCheckPerAccountThenTextMessages() {
        val o = MessagesOptions().toggled(OptionsScreen.INBOX, 1, accounts)
        assertEquals(listOf(true, false, true), o.inboxChecks(accounts))
        assertEquals(setOf("mail:2"), o.hiddenAccounts)
        val t = o.toggled(OptionsScreen.INBOX, 2, accounts)
        assertEquals(listOf(true, false, false), t.inboxChecks(accounts))
        assertEquals(o, t.toggled(OptionsScreen.INBOX, 2, accounts))
        assertEquals(MessagesOptions(), o.toggled(OptionsScreen.INBOX, 1, accounts)) // shown again
    }
}
