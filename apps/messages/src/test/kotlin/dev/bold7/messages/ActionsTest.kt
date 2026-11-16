package dev.bold7.messages

import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.ItemKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActionsTest {
    private val week = snapshot(
        sms("a", 12, 9, read = true, address = "+331"), sms("b", 12, 8, address = "+332"),
        sms("c", 11, 9, read = true, address = "+331"), sms("d", 10, 9, address = "+333"), sms("e", 10, 8, read = true, address = "+331"),
    )

    // 0 day 12, 1 a, 2 b, 3 day 11, 4 c, 5 day 10, 6 d, 7 e
    private val rows = inboxLines(week, Filter.ALL, null, format)

    // Menus (guide 7.1 p. 31, 94-95, B; sim-44, C)

    @Test fun aMessagesFullMenuActsOnItThenComposesAndSearches() {
        val unread = rows[2] as ItemLine
        assertEquals(
            listOf(
                Act.OPEN, Act.MARK_READ, Act.SAVE, Act.DELETE, Act.COMPOSE_EMAIL, Act.COMPOSE_OTHER, Act.SEARCH, Act.SEARCH_BY, Act.DISPLAY_STYLE,
                Act.VIEW_FOLDER, Act.VIEW_SAVED, Act.NOTIFIERS, Act.OPTIONS,
            ),
            fullMenu(unread).map { it.action },
        )
        assertEquals(listOf(false, false, false, false, true, false, true, false, false, false, false, true, false), fullMenu(unread).map { it.separatorAbove })
        assertEquals(Act.MARK_UNREAD, fullMenu(rows[1]).map { it.action }[1])
    }

    @Test fun aDateFieldsMenuHasThePriorActions() {
        val tail = listOf(Act.COMPOSE_EMAIL, Act.COMPOSE_OTHER, Act.SEARCH, Act.SEARCH_BY, Act.DISPLAY_STYLE, Act.VIEW_FOLDER, Act.VIEW_SAVED, Act.NOTIFIERS, Act.OPTIONS)
        assertEquals(listOf(Act.MARK_PRIOR_READ, Act.DELETE_PRIOR) + tail, fullMenu(rows[0]).map { it.action })
        assertEquals(tail, fullMenu(null).map { it.action })
        assertEquals(false, fullMenu(null).first().separatorAbove)
    }

    @Test fun composeOtherHoldsTheTextMessageAndSearchByIsAlwaysThere() {
        // sim-44, sim-45 (C): Compose Other and Search by, both with a submenu arrow, also on an empty inbox.
        for (line in listOf(rows[0], rows[2], null)) {
            val m = fullMenu(line)
            assertEquals(true, m.first { it.action == Act.COMPOSE_OTHER }.submenu)
            assertEquals(true, m.first { it.action == Act.SEARCH_BY }.submenu)
        }
        assertEquals(listOf(Act.COMPOSE_TEXT), composeOtherMenu().entries.map { it.action })
        assertEquals(listOf(Act.SEARCH_SENDER, Act.SEARCH_SUBJECT), searchByMenu(null).entries.map { it.action })
    }

    @Test fun theFullMenuEndsWithHelpAndCloseUnderOptions() {
        // sim-289, sim-290 (C): ... Message Notifiers, Options, Help, Close; not in the short menu.
        val m = menuOf(rows[2] as ItemLine)
        assertEquals(listOf(Act.OPTIONS, Act.HELP, Act.CLOSE), m.entries.takeLast(3).map { it.action })
        assertEquals(listOf(false, false, false), m.entries.takeLast(3).map { it.separatorAbove })
        assertEquals(false, shortMenu(rows[2]).any { it.action == Act.HELP || it.action == Act.CLOSE })
    }

    @Test fun theFullMenuOpensWithSwitchApplicationAndKeepsItsFocus() {
        val m = menuOf(rows[2] as ItemLine)
        assertEquals(listOf(Act.SWITCH, Act.OPEN), m.entries.take(2).map { it.action })
        assertEquals(Act.OPEN, m.entries[m.highlighted!!].action)
        assertEquals(listOf(false, true, false), m.entries.take(3).map { it.separatorAbove }) // alone in its group
    }

    @Test fun theShortMenuIsTheFullOnesActions() {
        // Without the submenus and the rows OS 7's short menu has no cell for (D).
        assertEquals(listOf(Act.OPEN, Act.MARK_READ, Act.DELETE, Act.COMPOSE_EMAIL, Act.COMPOSE, Act.SEARCH, Act.VIEW_FOLDER, Act.OPTIONS), shortMenu(rows[2]).map { it.action })
        assertEquals(listOf(Act.MARK_PRIOR_READ, Act.DELETE_PRIOR, Act.COMPOSE_EMAIL, Act.COMPOSE, Act.SEARCH, Act.VIEW_FOLDER, Act.OPTIONS), shortMenu(rows[0]).map { it.action })
    }

    // Shortcuts of the inbox (guide 7.1 p. 51-52, B)

    @Test fun theLettersOfTheGuide() {
        assertEquals(Act.COMPOSE, shortcut('c', alt = false))
        assertEquals(Act.TO_TOP, shortcut('T', alt = false))
        assertEquals(Act.TO_BOTTOM, shortcut('b', alt = false))
        assertEquals(Act.NEXT_UNREAD, shortcut('u', alt = false))
        assertEquals(Act.NEXT_RELATED, shortcut('j', alt = false))
        assertEquals(Act.PREVIOUS_RELATED, shortcut('k', alt = false))
        assertEquals(Act.TOGGLE_READ, shortcut('u', alt = true))
        assertEquals(Act.SHOW_INCOMING, shortcut('i', alt = true))
        assertEquals(Act.SHOW_OUTGOING, shortcut('o', alt = true))
        assertEquals(Act.SHOW_TEXT, shortcut('s', alt = true))
        assertEquals(Act.SHOW_CALLS, shortcut('p', alt = true))
        // W (flag), Alt+F (flagged) and Alt+V (voice mail): no source flags or leaves a voice mail.
        assertNull(shortcut('w', alt = false))
        assertNull(shortcut('v', alt = true))
        assertNull(shortcut('x', alt = false))
    }

    @Test fun theAltViewsAreTheFolderScreensFilters() {
        assertEquals(Filter.INCOMING, Act.SHOW_INCOMING.filter)
        assertEquals(Filter.OUTGOING, Act.SHOW_OUTGOING.filter)
        assertEquals(Filter.TEXT, Act.SHOW_TEXT.filter)
        assertEquals(Filter.CALLS, Act.SHOW_CALLS.filter)
        assertNull(Act.OPEN.filter)
    }

    // Moving

    @Test fun topBottomAndPages() {
        assertEquals(0, navigate(Act.TO_TOP, rows, 4, page = 3))
        assertEquals(7, navigate(Act.TO_BOTTOM, rows, 4, page = 3))
        assertEquals(7, navigate(Act.PAGE_DOWN, rows, 5, page = 3))
        assertEquals(3, navigate(Act.PAGE_DOWN, rows, 0, page = 3))
        assertEquals(1, navigate(Act.PAGE_UP, rows, 4, page = 3))
        assertEquals(0, navigate(Act.PAGE_UP, rows, 1, page = 3))
        assertNull(navigate(Act.TO_TOP, emptyList(), 0, page = 3))
        assertNull(navigate(Act.PAGE_DOWN, emptyList(), 0, page = 3))
    }

    @Test fun nextUnreadSkipsHeadersAndReadRows() {
        assertEquals(2, navigate(Act.NEXT_UNREAD, rows, 0, page = 3))
        assertEquals(6, navigate(Act.NEXT_UNREAD, rows, 2, page = 3))
        assertNull(navigate(Act.NEXT_UNREAD, rows, 6, page = 3))
    }

    @Test fun relatedMessagesShareTheirSender() {
        // a (+331) at 1, c at 4, e at 7.
        assertEquals(4, navigate(Act.NEXT_RELATED, rows, 1, page = 3))
        assertEquals(7, navigate(Act.NEXT_RELATED, rows, 4, page = 3))
        assertNull(navigate(Act.NEXT_RELATED, rows, 7, page = 3))
        assertEquals(1, navigate(Act.PREVIOUS_RELATED, rows, 4, page = 3))
        assertNull(navigate(Act.NEXT_RELATED, rows, 0, page = 3))
    }

    // What the provider writes

    private fun keys(vararg k: String) = k.map { ItemKey("sms", it) }

    @Test fun aRowIsReadOrDeletedWithItsConversationUpToIt() {
        val s = snapshot(sms("a", 12, 9, thread = "t"), sms("b", 12, 10, thread = "t"), sms("x", 12, 11))
        val row = s.items[1]
        assertEquals(keys("a", "b"), writeKeys(Request(Act.MARK_READ, item = row), s, Filter.ALL, null, paris))
        assertEquals(keys("a", "b"), writeKeys(Request(Act.DELETE, item = row), s, Filter.ALL, null, paris))
        assertEquals(keys("b"), writeKeys(Request(Act.MARK_UNREAD, item = row), s, Filter.ALL, null, paris))
    }

    @Test fun priorIsWhatTheViewShowsOnThatDayAndBeforeAsTheMenuSawIt() {
        val s = snapshot(sms("a", 12, 9), call("c", 11, 9), sms("b", 11, 8), sms("d", 10, 9))
        val day = LocalDate.of(2026, 11, 11)
        assertEquals(
            listOf(ItemKey("sms", "b"), ItemKey("calls", "c"), ItemKey("sms", "d")),
            writeKeys(Request(Act.MARK_PRIOR_READ, day = day), s, Filter.ALL, null, paris).sortedBy { it.sourceKey },
        )
        assertEquals(keys("b", "d"), writeKeys(Request(Act.DELETE_PRIOR, day = day), s, Filter.TEXT, null, paris).sortedBy { it.sourceKey })
        assertEquals(keys("d"), writeKeys(Request(Act.DELETE_PRIOR, day = day, asOf = at(10, 12)), s, Filter.TEXT, null, paris))
        assertEquals(keys("b"), writeKeys(Request(Act.DELETE_PRIOR, day = day), s, Filter.ALL, "tb", paris))
    }

    @Test fun theEchoesOfARowGoWithIt() {
        val s = snapshot(sms("a", 12, 9, text = "hi"), note("n", 12, 9, app = "sms.app", sender = "Alice", text = "hi"))
        assertEquals(listOf(ItemKey("sms", "a"), ItemKey("notifications", "n")), writeKeys(Request(Act.DELETE, item = s.items[0]), s, Filter.ALL, null, paris))
    }

    @Test fun neverADeletedItem() {
        val s = snapshot(sms("a", 12, 9, thread = "t").copy(deleted = true), sms("b", 12, 10, thread = "t"))
        assertEquals(keys("b"), writeKeys(Request(Act.DELETE, item = s.items[1]), s, Filter.ALL, null, paris))
    }

    @Test fun eachWritingActHasItsProviderMethod() {
        assertEquals(HubContract.METHOD_MARK_READ, Act.MARK_READ.method)
        assertEquals(HubContract.METHOD_MARK_READ, Act.MARK_PRIOR_READ.method)
        assertEquals(HubContract.METHOD_MARK_UNREAD, Act.MARK_UNREAD.method)
        assertEquals(HubContract.METHOD_DELETE, Act.DELETE.method)
        assertEquals(HubContract.METHOD_DELETE, Act.DELETE_PRIOR.method)
        assertNull(Act.OPEN.method)
    }

    // Opening

    @Test fun anItemOpensInItsOwnApp() {
        assertEquals(Opening.Conversation(listOf("+331")), opening(sms("a", 12, 9, address = "+331")))
        // "Call …?" of OS 7 (sim-104), asked by the Phone of Bold7; a withheld number, its log.
        assertEquals(Opening.Call("+33199000000"), opening(call("c", 12, 9)))
        assertEquals(Opening.CallLog, opening(call("c", 12, 9, number = "")))
        assertEquals(Opening.Notification("n", "com.app"), opening(note("n", 12, 9)))
        // a mail opens in Messages, a draft in Compose.
        val mail = HubItem("mail-1", "INBOX|7|3", ItemKind.EMAIL, 12, "Renée")
        assertEquals(Opening.Mail(ItemKey("mail-1", "INBOX|7|3")), opening(mail))
        assertEquals(Opening.Draft("d1"), opening(mail.copy(accountId = DRAFTS_ACCOUNT, sourceKey = "d1")))
    }

    @Test fun aGroupOpensOnTheWholeGroupNeverOnOneMember() {
        // The Hub names a group thread's members in its address, as smsto: joins them (HubSources).
        val group = mms("g", 12, 9, attachments = 0).copy(address = "+331;+332;+333")
        assertEquals(Opening.Conversation(listOf("+331", "+332", "+333")), opening(group))
        // No address known: the SMS app itself, not a conversation with somebody.
        assertEquals(Opening.Conversation(emptyList()), opening(mms("m", 12, 9, attachments = 1)))
    }

    // C composes a mail once an account is there (OS 7 composed an email from its
    // inbox), a text otherwise.
    @Test fun cComposesAMailWhenThereIsAnAccount() {
        assertEquals(Act.COMPOSE, shortcut('c', alt = false, mail = false))
        assertEquals(Act.COMPOSE_EMAIL, shortcut('c', alt = false, mail = true))
        assertEquals(Act.COMPOSE_EMAIL, shortcut('C', alt = false, mail = true))
        assertEquals(Act.TO_TOP, shortcut('t', alt = false, mail = true))
    }
}
