package dev.bold7.launcher.connections

import dev.bold7.platform.Connection
import dev.bold7.platform.Connection.MOBILE
import dev.bold7.platform.Connection.NFC
import dev.bold7.platform.Connection.WIFI
import dev.bold7.platform.ConnectionReadings
import dev.bold7.platform.ConnectionSwitches
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android's switches, by hand: a set turns the connection at once unless [refuse] names it. */
private class FakeSwitches(override val available: Set<Connection>, on: Set<Connection>) : ConnectionSwitches {
    var readings = ConnectionReadings(on)
    var refuse = emptySet<Connection>()
    val calls = mutableListOf<Pair<Connection, Boolean>>()
    var listener: ((ConnectionReadings) -> Unit)? = null

    override fun set(c: Connection, on: Boolean): Boolean {
        calls += c to on
        if (c in refuse) return false
        readings = readings.copy(on = if (on) readings.on + c else readings.on - c)
        listener?.invoke(readings)
        return true
    }

    override fun start(onChange: (ConnectionReadings) -> Unit) {
        listener = onChange
        onChange(readings)
    }

    override fun stop() {
        listener = null
    }
}

class ConnectionsControllerTest {
    private val now = Executor { it.run() }
    private val fake = FakeSwitches(setOf(MOBILE, WIFI, NFC), setOf(MOBILE, WIFI))
    private val opened = mutableListOf<Page>()
    private val c = ConnectionsController(fake, io = now, main = now)
    private var shown: ConnectionsState? = null

    @Test fun theAvailableConnectionsAreReadOnceAndOpeningShowsAndroidsState() {
        c.open { shown = it }
        assertEquals(listOf(MOBILE, WIFI, NFC), shown?.boxes)
        assertEquals(setOf(MOBILE, WIFI), shown?.readings?.on)
    }

    @Test fun aBoxSwitchesItsConnectionAndTheBoxFollowsAndroid() {
        c.open { shown = it }
        c.click(Item.Box(WIFI), opened::add)
        assertEquals(listOf(WIFI to false), fake.calls)
        assertEquals(setOf(MOBILE), shown?.readings?.on)
    }

    @Test fun aRefusedSwitchOpensAndroidsPage() {
        fake.refuse = setOf(NFC)
        c.open { shown = it }
        c.click(Item.Box(NFC), opened::add)
        assertEquals(listOf(Page.NFC), opened)
    }

    @Test fun restoreOutlivesThePanel() {
        c.open { shown = it }
        c.click(Item.AllOff, opened::add)
        c.close()
        assertNull(fake.listener)
        c.open { shown = it }
        assertTrue(shown!!.restoring)
        c.click(Item.AllOff, opened::add)
        assertEquals(setOf(MOBILE, WIFI), fake.readings.on)
    }

    @Test fun nothingIsToldAfterClose() {
        c.open { shown = it }
        c.close()
        shown = null
        fake.set(WIFI, false)
        assertNull(shown)
    }
}
