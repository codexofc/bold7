package dev.bold7.launcher.connections

import dev.bold7.platform.ConnectionSwitches
import java.util.concurrent.Executor

/**
 * Manage Connections' state for the launcher process: Android's switches, listened to only while
 * the panel shows ([open] to [close]), and All Off's memory, kept between two openings.
 * The memory lives as long as the process; a reboot turns the radio back on anyway (its
 * reason is the phone process'), preferences if the other three should be restored after one.
 * Switches go to [io] (binder calls); the panel is told on [main].
 */
internal class ConnectionsController(
    private val switches: ConnectionSwitches,
    private val io: Executor,
    private val main: Executor,
) {
    // Main thread only.
    private var state = ConnectionsState(emptySet())
    private var listener: ((ConnectionsState) -> Unit)? = null

    init {
        // PackageManager: once, off the main thread.
        io.execute {
            val available = switches.available
            main.execute { update(state.copy(available = available)) }
        }
    }

    fun open(onChange: (ConnectionsState) -> Unit) {
        listener = onChange
        onChange(state)
        switches.start { update(state.observed(it)) }
    }

    fun close() {
        listener = null
        switches.stop()
    }

    /** A click on [item]; [openPage] opens an Android page, on the main thread (a switch Android refused opens its own). */
    fun click(item: Item, openPage: (Page) -> Unit) {
        val (next, effects) = state.click(item)
        update(next)
        for (e in effects) when (e) {
            is Effect.Open -> openPage(e.page)
            is Effect.Switch -> io.execute {
                if (!switches.set(e.connection, e.on)) main.execute { openPage(refusedPage(e.connection)) }
            }
        }
    }

    private fun update(next: ConnectionsState) {
        state = next
        listener?.invoke(next)
    }
}
