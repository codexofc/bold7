package dev.bold7.platform

/** A connection of Manage Connections, in OS 7's order (gsm 006, C). */
enum class Connection { MOBILE, WIFI, BLUETOOTH, NFC }

/**
 * Android's connections as Manage Connections shows them: those [on] (turning on counts, turning
 * off does not), the mobile network's name while it serves ([network], "" otherwise), Android's
 * airplane mode, and whether an alarm is set.
 */
data class ConnectionReadings(
    val on: Set<Connection> = emptySet(),
    val network: String = "",
    val airplane: Boolean = false,
    val alarm: Boolean = false,
)

/**
 * Android's switches for Manage Connections, behind an interface of ours:
 * [SystemConnections] on the phone, a fake in the tests.
 */
interface ConnectionSwitches {
    /** The connections this phone has. */
    val available: Set<Connection>

    /** Turns [c] on or off; false if Android refused it (logged). A binder call: never on the main thread. */
    fun set(c: Connection, on: Boolean): Boolean

    /** Android's state now, then at each change, to [onChange] on the main thread, until [stop]. No polling. */
    fun start(onChange: (ConnectionReadings) -> Unit)

    fun stop()
}

/**
 * What a banner or Manage Connections shows in place of the phone's carrier name and next alarm
 * while the showcase is on (core/showcase): nothing real is read for them then.
 */
class ShowcaseMask(val carrier: String, val alarm: Boolean)

/** The network's name: [mask]'s carrier, or [real], read only without a mask. */
inline fun networkName(mask: ShowcaseMask?, real: () -> String): String = mask?.carrier ?: real()

/** Whether an alarm is set: [mask]'s, or [real], read only without a mask. */
inline fun alarmSet(mask: ShowcaseMask?, real: () -> Boolean): Boolean = mask?.alarm ?: real()
