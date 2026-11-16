package dev.bold7.launcher.connections

import dev.bold7.platform.Connection
import dev.bold7.platform.ConnectionReadings

/**
 * Manage Connections (docs/BBOS7-VISUEL.md §6), pure: what its items are and what a
 * click on one does. Android's pages stand in for OS 7's Options screens until they are drawn.
 */
enum class Page { AIRPLANE, MOBILE, WIFI, BLUETOOTH, NFC, ALARMS, NETWORKS, WIFI_NETWORKS, SERVICE_STATUS, OPTIONS }

/** What the trackpad's focus moves through, top to bottom; [Banner] is the banner over the panel, which keeps the focus first (sim-23, sim-112, C). */
sealed interface Item {
    /** Whether a click on it closes the panel: the boxes and the button keep it open (sim-117, sim-119, C). */
    val closes: Boolean get() = true

    data object Banner : Item
    data class Box(val connection: Connection) : Item {
        override val closes get() = false
    }
    data object AllOff : Item {
        override val closes get() = false
    }
    data object Alarm : Item
    data class Entry(val page: Page) : Item
}

sealed interface Effect {
    data class Switch(val connection: Connection, val on: Boolean) : Effect
    data class Open(val page: Page) : Effect
}

/** Options and Status (sim-24, gsm 007, C). */
val ENTRIES = listOf(Page.NETWORKS, Page.WIFI_NETWORKS, Page.SERVICE_STATUS, Page.OPTIONS)

/**
 * The phone's connections, Android's [readings], and [restore]: what All Off turned off, while
 * the button reads Restore (sim-117, C), null otherwise.
 */
data class ConnectionsState(
    val available: Set<Connection>,
    val readings: ConnectionReadings = ConnectionReadings(),
    val restore: Set<Connection>? = null,
) {
    /** In OS 7's order (gsm 006, C). */
    val boxes: List<Connection> = Connection.entries.filter { it in available }

    val items: List<Item> = listOf(Item.Banner) + boxes.map { Item.Box(it) } + Item.AllOff + Item.Alarm + ENTRIES.map { Item.Entry(it) }

    val restoring get() = restore != null

    fun isOn(c: Connection) = c in readings.on

    /**
     * The green text left of a box (sim-23, C): the network's name for the mobile network while it
     * serves, [on] ("On") for the others while on; nothing when off.
     */
    fun status(c: Connection, on: String): String? = when {
        !isOn(c) -> null
        c == Connection.MOBILE -> readings.network.ifBlank { null }
        else -> on
    }

    /** Android's new state. It never ends a Restore: the radio takes seconds to go off (D). */
    fun observed(next: ConnectionReadings) = copy(readings = next)

    /**
     * A click on [item]: the next state and what to do. A box switches its connection, and turned
     * on by hand ends a Restore (sim-119, C); All Off turns off what is on and keeps it (guide
     * p. 258, B; sim-117, C), Restore turns it back on. The boxes follow Android's readings, never
     * the click itself.
     */
    fun click(item: Item): Pair<ConnectionsState, List<Effect>> = when (item) {
        Item.Banner -> this to emptyList()
        is Item.Box -> if (isOn(item.connection)) this to listOf(Effect.Switch(item.connection, false))
        else copy(restore = null) to listOf(turnOn(item.connection))
        Item.AllOff -> when (val r = restore) {
            null -> copy(restore = boxes.filter(::isOn).toSet()) to boxes.filter(::isOn).map { Effect.Switch(it, false) }
            else -> copy(restore = null) to boxes.filter { it in r }.map(::turnOn)
        }
        Item.Alarm -> this to listOf(Effect.Open(Page.ALARMS))
        is Item.Entry -> this to listOf(Effect.Open(item.page))
    }

    // Android's airplane mode is not ours to turn (a signature permission): with it on,
    // the radio stays off whatever we clear, so its page is where to turn it off.
    private fun turnOn(c: Connection): Effect =
        if (c == Connection.MOBILE && readings.airplane) Effect.Open(Page.AIRPLANE) else Effect.Switch(c, true)
}

/** Android's page for a connection whose switch Android refused (its airplane mode, the hotspot, a permission missing). */
fun refusedPage(c: Connection) = when (c) {
    Connection.MOBILE -> Page.MOBILE
    Connection.WIFI -> Page.WIFI
    Connection.BLUETOOTH -> Page.BLUETOOTH
    Connection.NFC -> Page.NFC
}
