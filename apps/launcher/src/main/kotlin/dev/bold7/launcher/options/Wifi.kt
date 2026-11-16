package dev.bold7.launcher.options

import dev.bold7.platform.WifiSaved
import dev.bold7.platform.WifiSecurity
import dev.bold7.platform.WifiState

// Options > Networks and Connections > Wi-Fi Network, pure (WifiTest): the networks
// a scan saw, the Show Open networks only box and Saved Wi-Fi Networks of the guide 7.1 p. 265 to
// 267 (B), what a click does. WifiActivity draws it.

/** A row of the Wi-Fi Network screen. */
sealed interface WifiRow {
    /** A network the scan saw, the saved configuration that joins it if any, [connected] if Android is on it. */
    data class Network(val ssid: String, val bars: Int, val security: WifiSecurity, val saved: WifiSaved?, val connected: Boolean) : WifiRow

    /** "Show Open networks only" (guide p. 265, B). */
    data class OpenOnly(val checked: Boolean) : WifiRow

    /** "Saved Wi-Fi Networks" (guide p. 266, B). */
    data object Saved : WifiRow
}

/** What a click on the focus asks of the screen. */
sealed interface WifiAction {
    data object None : WifiAction

    data object OpenSaved : WifiAction

    /** Connect to the saved network [id]. */
    data class Connect(val id: Int, val ssid: String) : WifiAction

    /** Join [ssid]: the password asked first when [password]. */
    data class Join(val ssid: String, val security: WifiSecurity, val password: Boolean) : WifiAction

    /** A network Bold7 does not join (WEP, Enterprise): said, nothing done. */
    data class Unsupported(val ssid: String, val security: WifiSecurity) : WifiAction
}

/** The security as the network's grey line says it (D: the guide's Security Type names, p. 267). */
fun securityLabel(s: WifiSecurity): String = when (s) {
    WifiSecurity.OPEN -> "Open"
    WifiSecurity.OWE -> "Enhanced Open"
    WifiSecurity.PERSONAL -> "WPA/WPA2 Personal"
    WifiSecurity.WPA3 -> "WPA3 Personal"
    WifiSecurity.WEP -> "WEP"
    WifiSecurity.ENTERPRISE -> "WPA/WPA2 Enterprise"
}

/** Whether the lock shows: every network but an open one. */
fun locked(s: WifiSecurity) = s != WifiSecurity.OPEN && s != WifiSecurity.OWE

/**
 * The screen: Android's [state], the box [openOnly], [focus] an index into [rows]. The networks:
 * one row per name (the strongest of its access points), the one Android is on first, then by
 * signal and name (D).
 */
data class WifiScreen(val state: WifiState = WifiState(), val openOnly: Boolean = false, val focus: Int = 0) {
    val networks: List<WifiRow.Network>
        get() = state.seen
            .groupBy { it.ssid }
            .map { (ssid, all) ->
                val best = all.maxBy { it.bars }
                val saved = state.saved.firstOrNull { it.ssid == ssid && it.security == best.security } ?: state.saved.firstOrNull { it.ssid == ssid }
                WifiRow.Network(ssid, best.bars, best.security, saved, ssid == state.connected)
            }
            .filter { !openOnly || !locked(it.security) }
            .sortedWith(compareBy<WifiRow.Network>({ !it.connected }, { -it.bars }, { it.ssid.lowercase() }))

    /** The networks, then the box and Saved Wi-Fi Networks (D: their place under the list). */
    val rows: List<WifiRow> get() = networks + WifiRow.OpenOnly(openOnly) + WifiRow.Saved

    /** The line over the list when there is no network to show, null otherwise (D). */
    val notice: String?
        get() = when {
            !state.on -> "Wi-Fi is turned off. Turn it on in Manage Connections."
            !state.allowed -> "Without location access, Android lists no networks."
            networks.isEmpty() -> if (openOnly) "No open Wi-Fi networks found." else "No Wi-Fi networks found."
            else -> null
        }

    /** The trackpad moves the focus and stops at the ends. */
    fun step(down: Boolean) = copy(focus = (focus + if (down) 1 else -1).coerceIn(0, rows.lastIndex))

    /** Android's next state; the focus stays on the row it was on, by name, else at its index. */
    fun with(next: WifiState): WifiScreen {
        val was = rows.getOrNull(focus)
        val after = copy(state = next)
        val same = after.rows.indexOfFirst { it == was || (it is WifiRow.Network && was is WifiRow.Network && it.ssid == was.ssid) }
        return after.copy(focus = if (same >= 0) same else focus.coerceIn(0, after.rows.lastIndex))
    }

    /** A click on [row] (a tap) or on the focus: the box toggles here, the rest is the action's. */
    fun click(row: Int = focus): Pair<WifiScreen, WifiAction> {
        val at = copy(focus = row.coerceIn(0, rows.lastIndex))
        return when (val r = at.rows[at.focus]) {
            is WifiRow.OpenOnly -> {
                val toggled = at.copy(openOnly = !openOnly)
                // The box stays under the focus as the list above it shrinks or grows.
                toggled.copy(focus = toggled.rows.indexOfFirst { it is WifiRow.OpenOnly }) to WifiAction.None
            }
            WifiRow.Saved -> at to WifiAction.OpenSaved
            is WifiRow.Network -> at to when {
                r.connected -> WifiAction.None
                r.saved != null -> WifiAction.Connect(r.saved.id, r.ssid)
                r.security == WifiSecurity.WEP || r.security == WifiSecurity.ENTERPRISE -> WifiAction.Unsupported(r.ssid, r.security)
                else -> WifiAction.Join(r.ssid, r.security, password = locked(r.security))
            }
        }
    }
}

/** Saved Wi-Fi Networks (guide p. 266, B): Android's saved networks by name, [focus] among them. */
data class SavedWifiScreen(val saved: List<WifiSaved> = emptyList(), val focus: Int = 0) {
    val rows: List<WifiSaved> get() = saved.sortedBy { it.ssid.lowercase() }

    fun step(down: Boolean) = copy(focus = (focus + if (down) 1 else -1).coerceIn(0, (rows.size - 1).coerceAtLeast(0)))

    fun with(next: List<WifiSaved>): SavedWifiScreen {
        val was = rows.getOrNull(focus)?.id
        val after = copy(saved = next)
        val same = after.rows.indexOfFirst { it.id == was }
        return after.copy(focus = if (same >= 0) same else focus.coerceIn(0, (after.rows.size - 1).coerceAtLeast(0)))
    }

    val focused: WifiSaved? get() = rows.getOrNull(focus)
}

/**
 * Why Android would refuse [password] for [security], or null (WifiConfiguration's rules: a
 * WPA/WPA2 passphrase of 8 to 63 printable ASCII characters, or 64 hex digits; WPA3 any, not empty).
 * The words are D.
 */
fun passwordProblem(security: WifiSecurity, password: CharSequence): String? = when (security) {
    WifiSecurity.PERSONAL -> when {
        password.length == 64 && password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } -> null
        password.length !in 8..63 -> "The password must be 8 to 63 characters long."
        password.any { it.code !in 0x20..0x7e } -> "The password can only use the characters of the keyboard."
        else -> null
    }
    WifiSecurity.WPA3 -> if (password.isEmpty()) "Type the password of the network." else null
    else -> null
}
