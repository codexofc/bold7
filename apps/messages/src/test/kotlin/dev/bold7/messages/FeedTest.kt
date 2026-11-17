package dev.bold7.messages

import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.ItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle

/** A hand-written reader (docs/ARCHITECTURE.md §2): what it holds, what it was asked. */
private class FakeReader(var snapshot: Snapshot?) : InboxReader {
    var reads = 0
    var fail = false
    val writes = mutableListOf<Pair<String, List<ItemKey>>>()
    val seen = mutableListOf<Map<String, Long>>()

    override fun read(): Snapshot? {
        reads++
        if (fail) throw IllegalStateException("provider")
        return snapshot
    }

    override fun format() = format
    override fun write(method: String, keys: List<ItemKey>) {
        writes += method to keys
    }

    override fun markSeen(upTo: Map<String, Long>) {
        seen += upTo
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeedTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val errors = mutableListOf<RuntimeException>()
    private val reader = FakeReader(snapshot(sms("a", 12, 9, added = 10), call("c", 12, 8).copy(addedAt = 20)))
    private val feed = Feed(reader, dispatcher, scope, { errors += it })

    private fun keys() = feed.state.value.lines.filterIsInstance<ItemLine>().map { it.item.sourceKey }

    @Test fun aReloadReadsAndWorksTheLinesOut() {
        feed.reload()
        scope.advanceUntilIdle()
        assertEquals(listOf("a", "c"), keys())
        assertEquals(true, feed.state.value.loaded)
        assertEquals(1, reader.reads)
    }

    @Test fun anotherViewIsWorkedOutFromTheLastReadWithoutReading() {
        feed.reload()
        scope.advanceUntilIdle()
        feed.show(Filter.CALLS, null)
        scope.advanceUntilIdle()
        assertEquals(listOf("c"), keys())
        assertEquals(Filter.CALLS, feed.state.value.filter)
        assertEquals(1, reader.reads)
    }

    @Test fun aFailedReadKeepsTheLinesAndIsReported() {
        feed.reload()
        scope.advanceUntilIdle()
        reader.fail = true
        feed.reload()
        scope.advanceUntilIdle()
        assertEquals(listOf("a", "c"), keys())
        assertEquals(1, errors.size)
    }

    @Test fun nothingToReadIsLoadedAndEmpty() {
        reader.snapshot = null
        feed.reload()
        scope.advanceUntilIdle()
        assertEquals(true, feed.state.value.loaded)
        assertEquals(emptyList(), feed.state.value.lines)
    }

    @Test fun whileViewedWhatIsNewIsSeen() {
        feed.reload()
        scope.advanceUntilIdle()
        assertEquals(emptyList(), reader.seen)
        feed.view(true)
        scope.advanceUntilIdle()
        assertEquals(listOf(mapOf("sms" to 10L, "calls" to 20L)), reader.seen)
    }

    // Text Messages: opened on the texts, it sees them alone.
    @Test fun aViewSeesOnlyWhatItShows() {
        feed.show(Filter.TEXT, null)
        feed.view(true)
        feed.reload()
        scope.advanceUntilIdle()
        assertEquals(listOf(mapOf("sms" to 10L)), reader.seen)
    }

    @Test fun theMadeUpItemsAreNeverWrittenNorSeen() {
        reader.snapshot = reader.snapshot!!.copy(demo = true)
        feed.view(true)
        feed.reload()
        scope.advanceUntilIdle()
        feed.act(Request(Act.DELETE, item = reader.snapshot!!.items[0]))
        scope.advanceUntilIdle()
        assertEquals(emptyList(), reader.seen)
        assertEquals(emptyList(), reader.writes)
    }

    @Test fun anActWritesThroughTheProvider() {
        feed.act(Request(Act.DELETE, item = reader.snapshot!!.items[0]))
        scope.advanceUntilIdle()
        assertEquals(emptyList(), reader.writes) // nothing before the first read
        feed.reload()
        scope.advanceUntilIdle()
        feed.act(Request(Act.DELETE, item = reader.snapshot!!.items[0]))
        feed.act(Request(Act.OPEN, item = reader.snapshot!!.items[0]))
        scope.advanceUntilIdle()
        assertEquals(listOf(HubContract.METHOD_DELETE to listOf(ItemKey("sms", "a"))), reader.writes)
    }

    @Test fun aLongRangeIsWrittenAtMostKeysMaxAtATime() {
        val many = (0 until HubContract.KEYS_MAX + 100).map { sms("m$it", 12, 9) }
        reader.snapshot = snapshot(*many.toTypedArray())
        feed.reload()
        scope.advanceUntilIdle()
        feed.act(Request(Act.MARK_UNREAD, rows = many))
        scope.advanceUntilIdle()
        assertEquals(listOf(HubContract.KEYS_MAX, 100), reader.writes.map { it.second.size })
        assertEquals(many.map { ItemKey("sms", it.sourceKey) }, reader.writes.flatMap { it.second })
    }

    @Test fun anActOnTextsAsksGoogleMessagesTooBeforeTheHub() {
        val bridged = mutableListOf<List<ApiCall>>()
        val reader = FakeReader(snapshot(sms("sms:5", 12, 9, thread = "8"), sms("sms:4", 12, 8, thread = "8"), call("c", 12, 7)))
        val feed = Feed(reader, dispatcher, scope, { errors += it }) { bridged += it }
        feed.reload()
        scope.advanceUntilIdle()
        feed.act(Request(Act.MARK_READ, item = reader.snapshot!!.items[0]))
        feed.act(Request(Act.DELETE, item = reader.snapshot!!.items[0]))
        feed.act(Request(Act.MARK_UNREAD, item = reader.snapshot!!.items[0]))
        scope.advanceUntilIdle()
        assertEquals(
            listOf(
                listOf(ApiCall.MarkConversationRead(8)),
                listOf(ApiCall.Delete("content://sms/5"), ApiCall.Delete("content://sms/4")),
            ),
            bridged,
        )
        assertEquals(3, reader.writes.size)
    }
}
