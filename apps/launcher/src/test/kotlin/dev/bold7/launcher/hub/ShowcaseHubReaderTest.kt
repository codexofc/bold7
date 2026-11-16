package dev.bold7.launcher.hub

import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.SHOWCASE_KEY
import dev.bold7.launcher.notices.NoticeNames
import dev.bold7.launcher.notices.NoticeRow
import dev.bold7.showcase.Showcase
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

private val format = HubFormat(ZoneId.of("America/Toronto"), Locale.ENGLISH, false)

/** The Hub's reader, failing at any call: the showcase must reach none of it. */
private class TrippedHub : HubReader {
    private fun real(): Nothing = error("real Hub reached in the showcase")
    override fun read() = real()
    override fun format() = real()
    override fun write(method: String, keys: List<ItemKey>) = real()
}

class ShowcaseHubReaderTest {
    private val names = NoticeNames("Messages", "Text Messages", "Phone", "Calendar", "Missed Call")

    private fun switched(on: Boolean, real: () -> HubReader = { TrippedHub() }) =
        SwitchedHubReader({ on }, real, ShowcaseHubReader { format })

    @Test fun theBannerTheStarsAndTheListComeFromTheShowcase() = runTest {
        val feed = HubFeed(switched(true), StandardTestDispatcher(testScheduler), backgroundScope, names, { it }) { throw it }
        feed.reload()
        testScheduler.runCurrent()
        val s = feed.state.value
        assertTrue(s.loaded)
        assertEquals(mapOf(ItemKind.SMS to 3, ItemKind.EMAIL to 2, ItemKind.MISSED_CALL to 1), s.badges.counts)
        assertEquals(Showcase.unread, s.badges.counts.mapKeys { it.key.name })
        assertTrue(s.badges.hub && s.badges.texts)
        // Bold7's own Phone carries the missed call's star, Messages the texts'.
        assertTrue("dev.bold7.phone" in s.badges.apps)
        val rows = s.notices.filterIsInstance<NoticeRow>()
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.item.sourceKey.startsWith(SHOWCASE_KEY) })
    }

    @Test fun theHubIsNeverReadNorWrittenWhileItIsOn() {
        val reader = switched(true)
        assertNotNull(reader.read())
        assertEquals(format, reader.format())
        reader.write(HubContract.METHOD_MARK_READ, listOf(ItemKey("showcase-sms", "showcase:t1:0")))
    }

    @Test fun theHubIsReadAgainWhenItIsOff() {
        var built = 0
        val hub = object : HubReader {
            override fun read(): HubSnapshot? = HubSnapshot(emptyList(), null, null)
            override fun format() = format
            override fun write(method: String, keys: List<ItemKey>) = Unit
        }
        val reader = switched(false) { built++; hub }
        assertEquals(0, reader.read()!!.items.size)
        assertEquals(1, built)
        assertFalse(reader.read()!!.demo)
    }
}
