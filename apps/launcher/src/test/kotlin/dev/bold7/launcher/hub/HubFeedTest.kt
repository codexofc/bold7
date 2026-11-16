package dev.bold7.launcher.hub

import dev.bold7.hub.data.Account
import dev.bold7.hub.data.AccountKind
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.ItemKind
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Hub's provider, by hand: what it answers next, and how often it was asked. */
private class FakeReader : HubReader {
    var answer: HubSnapshot? = HubSnapshot(emptyList(), null, null)
    var failure: RuntimeException? = null
    var reads = 0
    override fun read(): HubSnapshot? {
        reads++
        failure?.let { throw it }
        return answer
    }
    override fun format() = HubFormat(ZoneId.of("Europe/Paris"), Locale.US, false)
    val writes = mutableListOf<Pair<String, List<ItemKey>>>()
    override fun write(method: String, keys: List<ItemKey>) {
        writes += method to keys
    }
}

private fun snapshot(vararg keys: String) =
    HubSnapshot(keys.map { HubItem("sms", it, ItemKind.SMS, 1_000L * it.length, "s$it") }, null, null)

class HubFeedTest {
    @Test fun nothingIsShownBeforeTheFirstRead() = runTest {
        val feed = HubFeed(FakeReader(), StandardTestDispatcher(testScheduler), backgroundScope) {}
        assertFalse(feed.state.value.loaded)
        feed.reload()
        testScheduler.runCurrent()
        assertTrue(feed.state.value.loaded)
    }

    @Test fun changesWaitingForTheReadMakeOneRead() = runTest {
        val reader = FakeReader()
        val feed = HubFeed(reader, StandardTestDispatcher(testScheduler), backgroundScope) {}
        repeat(3) { feed.reload() }
        testScheduler.runCurrent()
        assertEquals(1, reader.reads)
        feed.reload()
        testScheduler.runCurrent()
        assertEquals(2, reader.reads)
    }

    @Test fun aReadThatThrowsIsReportedAndTheNextOneStillComes() = runTest {
        val reader = FakeReader().apply { failure = IllegalStateException("bug") }
        val errors = mutableListOf<Throwable>()
        val feed = HubFeed(reader, StandardTestDispatcher(testScheduler), backgroundScope) { errors += it }
        feed.reload()
        testScheduler.runCurrent()
        assertEquals(1, errors.size)
        reader.failure = null
        reader.answer = HubSnapshot(
            listOf(HubItem("sms", "1", ItemKind.SMS, 1, "Ann", addedAt = 5)), "sms.app", null,
            listOf(Account("sms", AccountKind.TEXT_MESSAGES, "", seenAt = 0)),
        )
        feed.reload()
        testScheduler.runCurrent()
        assertEquals(mapOf(ItemKind.SMS to 1), feed.state.value.badges.counts)
    }

    @Test fun openingARowReadsItsConversationFromTheLastRead() = runTest {
        val reader = FakeReader().apply { answer = snapshot("a", "bb") }
        val feed = HubFeed(reader, StandardTestDispatcher(testScheduler), backgroundScope) {}
        feed.markRead(reader.answer!!.items[0])
        testScheduler.runCurrent()
        assertEquals(emptyList(), reader.writes) // before the first read, nothing is named
        feed.reload()
        testScheduler.runCurrent()
        feed.markRead(reader.answer!!.items[0])
        testScheduler.runCurrent()
        assertEquals(listOf(HubContract.METHOD_MARK_READ to listOf(ItemKey("sms", "a"))), reader.writes)
    }

    @Test fun aLongReadGoesInCallsTheProviderTakes() = runTest {
        val items = List(HubContract.KEYS_MAX + 1) { HubItem("sms", "k$it", ItemKind.SMS, 1, "s", conversationId = "c") }
        val reader = FakeReader().apply { answer = HubSnapshot(items, null, null) }
        val feed = HubFeed(reader, StandardTestDispatcher(testScheduler), backgroundScope) {}
        feed.reload()
        testScheduler.runCurrent()
        feed.markRead(items[0])
        testScheduler.runCurrent()
        assertEquals(listOf(HubContract.KEYS_MAX, 1), reader.writes.map { it.second.size })
    }

    @Test fun theDemoIsBounded() {
        assertEquals(HUB_DEMO_MAX, demoSnapshot(1_000_000, 0).items.size)
        assertEquals(200, demoSnapshot(200, 0).items.size)
        // Its rows say what they are, should it ever stay on.
        assertTrue(demoSnapshot(3, 0).items.all { it.sender.startsWith("Demo") })
    }

    @Test fun theDemoHasItsAccounts() {
        val demo = demoSnapshot(12, 0)
        assertEquals(demo.items.map { it.accountId }.toSet(), demo.accounts.map { it.id }.toSet())
    }
}
