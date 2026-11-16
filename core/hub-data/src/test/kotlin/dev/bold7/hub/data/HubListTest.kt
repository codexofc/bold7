package dev.bold7.hub.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val PARIS = ZoneId.of("Europe/Paris")
private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
    ZonedDateTime.of(y, m, d, h, min, 0, 0, PARIS).toInstant().toEpochMilli()

private fun item(
    key: String,
    time: Long,
    account: String = "sms",
    read: Boolean = false,
    flagged: Boolean = false,
    box: Box = Box.INBOX,
    folder: String? = null,
    conversation: String? = null,
    level1: Boolean = false,
) = HubItem(
    accountId = account, sourceKey = key, kind = ItemKind.SMS, timestamp = time, sender = "s$key",
    read = read, flagged = flagged, box = box, folder = folder, conversationId = conversation, level1 = level1,
)

private fun List<HubRow>.keys() = map {
    when (it) {
        is HubRow.Day -> it.date.toString()
        is HubRow.Entry -> it.item.sourceKey
    }
}

class HubViewTest {
    @Test fun choosingAnAccountForgetsTheFolderOfThePreviousOneAndKeepsThePinch() {
        val view = HubView(account = "mail", folder = "Sent", pinch = Pinch.UNREAD)
        assertEquals(HubView(account = "sms", pinch = Pinch.UNREAD), view.reduce(HubViewEvent.AccountChosen("sms")))
        assertEquals(HubView(pinch = Pinch.UNREAD), view.reduce(HubViewEvent.AccountChosen(null)))
    }

    @Test fun aFolderNeedsAnAccount() {
        assertEquals(HubView(), HubView().reduce(HubViewEvent.FolderChosen("Inbox")))
        assertEquals(HubView("mail", "Inbox"), HubView("mail").reduce(HubViewEvent.FolderChosen("Inbox")))
        assertEquals(HubView("mail"), HubView("mail", "Inbox").reduce(HubViewEvent.FolderChosen(null)))
    }

    @Test fun pinchFiltersAndSpreadUnfilters() {
        val pinched = HubView("mail").reduce(HubViewEvent.Pinched(Pinch.FLAGGED))
        assertEquals(HubView("mail", pinch = Pinch.FLAGGED), pinched)
        assertEquals(HubView("mail"), pinched.reduce(HubViewEvent.Spread))
    }

    @Test fun theHubShowsEveryAccountAnAccountOnlyItsOwn() {
        val sms = item("1", 0)
        val mail = item("2", 0, account = "mail", folder = "Inbox")
        assertTrue(HubView().shows(sms) && HubView().shows(mail))
        assertEquals(listOf(mail), listOf(sms, mail).filter(HubView("mail")::shows))
        assertEquals(listOf(mail), listOf(sms, mail).filter(HubView("mail", "Inbox")::shows))
        assertEquals(emptyList(), listOf(sms, mail).filter(HubView("mail", "Sent")::shows))
    }

    @Test fun noViewShowsADeletedItem() {
        // Deleted in the Hub : kept as a mark so its source does not bring it back.
        val deleted = item("1", 0).copy(deleted = true)
        assertEquals(emptyList(), listOf(deleted).filter(HubView()::shows))
        assertEquals(emptyList(), listOf(deleted).filter(HubView(pinch = Pinch.UNREAD)::shows))
    }

    @Test fun eachPinchCriterion() {
        val items = listOf(
            item("unread", 0),
            item("read", 0, read = true),
            item("flagged", 0, read = true, flagged = true),
            item("draft", 0, read = true, box = Box.DRAFT),
            item("sent", 0, read = true, box = Box.SENT),
            item("level1", 0, read = true, level1 = true),
        )
        fun pinched(p: Pinch) = items.filter(HubView(pinch = p)::shows).map { it.sourceKey }
        assertEquals(listOf("unread"), pinched(Pinch.UNREAD))
        assertEquals(listOf("flagged"), pinched(Pinch.FLAGGED))
        assertEquals(listOf("draft"), pinched(Pinch.DRAFTS))
        assertEquals(listOf("sent"), pinched(Pinch.SENT))
        assertEquals(listOf("level1"), pinched(Pinch.LEVEL1))
    }
}

class HubRowsTest {
    @Test fun newestFirstWithAHeaderAtEachLocalDay() {
        val rows = hubRows(
            listOf(
                item("old", at(2026, 11, 10, 9)),
                item("late", at(2026, 11, 12, 23, 30)),
                item("early", at(2026, 11, 12, 0, 15)),
                item("mid", at(2026, 11, 11, 12)),
            ),
            HubView(), PARIS, conversations = false,
        )
        assertEquals(listOf("2026-11-12", "late", "early", "2026-11-11", "mid", "2026-11-10", "old"), rows.keys())
    }

    @Test fun theDayIsTheLocalOne() {
        // 23:30 UTC on the 11th is 00:30 on the 12th in Paris.
        val t = ZonedDateTime.of(2026, 11, 11, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(HubRow.Day(LocalDate.of(2026, 11, 12)), hubRows(listOf(item("a", t)), HubView(), PARIS, false).first())
    }

    @Test fun aSummerEveningInUtcIsTheNextDayInParis() {
        // 22:30 UTC in July is 00:30 the next day in Paris (UTC+2 in summer).
        val t = ZonedDateTime.of(2026, 7, 14, 22, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(HubRow.Day(LocalDate.of(2026, 7, 15)), hubRows(listOf(item("a", t)), HubView(), PARIS, false).first())
    }

    @Test fun equalTimesKeepAStableOrder() {
        val t = at(2026, 11, 12, 10)
        val a = hubRows(listOf(item("b", t), item("a", t)), HubView(), PARIS, false)
        val b = hubRows(listOf(item("a", t), item("b", t)), HubView(), PARIS, false)
        assertEquals(a, b)
    }

    @Test fun aConversationIsOneLineAtItsLatestMessageWithItsCounts() {
        val rows = hubRows(
            listOf(
                item("c1", at(2026, 11, 11, 9), conversation = "t1", read = true),
                item("x", at(2026, 11, 12, 8)),
                item("c2", at(2026, 11, 12, 9), conversation = "t1"),
                item("c3", at(2026, 11, 10, 9), conversation = "t1", read = true),
                // Same thread id in another account: another conversation.
                item("m", at(2026, 11, 10, 10), account = "mail", conversation = "t1", read = true),
            ),
            HubView(), PARIS, conversations = true,
        )
        assertEquals(listOf("2026-11-12", "c2", "x", "2026-11-10", "m"), rows.keys())
        val thread = rows[1] as HubRow.Entry
        assertEquals(3, thread.count)
        assertEquals(1, thread.unread)
        assertEquals(HubRow.Entry(item("x", at(2026, 11, 12, 8)), count = 1, unread = 1), rows[2])
    }

    @Test fun theFilterComesBeforeTheConversations() {
        val rows = hubRows(
            listOf(
                item("c1", at(2026, 11, 12, 9), conversation = "t1"),
                item("c2", at(2026, 11, 12, 10), conversation = "t1", read = true),
            ),
            HubView(pinch = Pinch.UNREAD), PARIS, conversations = true,
        )
        assertEquals(listOf("2026-11-12", "c1"), rows.keys())
        assertEquals(1, (rows[1] as HubRow.Entry).count)
    }

    @Test fun nothingShownNoHeader() {
        assertEquals(emptyList(), hubRows(listOf(item("a", 0, read = true)), HubView(pinch = Pinch.UNREAD), PARIS, false))
    }
}
