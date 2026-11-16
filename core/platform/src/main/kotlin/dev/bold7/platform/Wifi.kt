package dev.bold7.platform

/**
 * A Wi-Fi network's security as Options' Wi-Fi Network screen joins it: open,
 * Enhanced Open (OWE), WPA/WPA2 Personal (PSK, also the PSK+SAE transition mode), WPA3 Personal
 * only (SAE), or the two Bold7 does not join (WEP, which Android 14 refuses by default, and
 * Enterprise, whose certificates and identity have no screen here).
 */
enum class WifiSecurity { OPEN, OWE, PERSONAL, WPA3, WEP, ENTERPRISE }

/** A network the last scan saw: its [ssid], its [bars] (1 to 3, the theme's three levels), its [security]. */
data class WifiSeen(val ssid: String, val bars: Int, val security: WifiSecurity)

/** A network Android keeps (its WifiConfiguration's [id]). */
data class WifiSaved(val id: Int, val ssid: String, val security: WifiSecurity)

/**
 * Android's Wi-Fi as the screen shows it: [on], whether the scan results may be read at all
 * ([allowed]: location permission granted and location on, WifiPermissionsUtil of the Q25), what
 * the last scan saw, what Android keeps, and the [connected] network's name or null.
 */
data class WifiState(
    val on: Boolean = false,
    val allowed: Boolean = false,
    val seen: List<WifiSeen> = emptyList(),
    val saved: List<WifiSaved> = emptyList(),
    val connected: String? = null,
)

/**
 * Android's Wi-Fi for Options' Wi-Fi Network screen, behind an interface of ours
 *: [SystemWifi] on the phone, a fake in the tests. [start] and [stop] on
 * the main thread; the others are binder calls: never on the main thread. A password handed to
 * [join] goes into Android's configuration and nowhere else: never logged, never kept.
 */
interface WifiNetworks {
    /** The state now, then at each scan or change, to [onChange] on the main thread, until [stop]. No polling. */
    fun start(onChange: (WifiState) -> Unit)

    fun stop()

    /** Asks Android for a scan; false if it refused (throttled, not allowed). */
    fun scan(): Boolean

    /** Saves [ssid] with [password] (null for an open network) and connects to it; false if Android refused. */
    fun join(ssid: String, security: WifiSecurity, password: CharSequence?): Boolean

    /** Connects to the saved network [id]. */
    fun connect(id: Int): Boolean

    /** Forgets the saved network [id]. */
    fun forget(id: Int): Boolean
}

/** The security a scan result's capabilities ("[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS]") ask for. */
fun wifiSecurity(capabilities: String): WifiSecurity {
    val c = capabilities.uppercase()
    return when {
        "PSK" in c -> WifiSecurity.PERSONAL
        "SAE" in c -> WifiSecurity.WPA3
        "EAP" in c -> WifiSecurity.ENTERPRISE
        "WEP" in c -> WifiSecurity.WEP
        "OWE" in c -> WifiSecurity.OWE
        else -> WifiSecurity.OPEN
    }
}

/** Android's signal [level] out of 0..[max] (WifiManager.calculateSignalLevel) as the theme's 1 to 3 bars. */
fun wifiBars(level: Int, max: Int): Int = (level.coerceIn(0, max) * 3 / (max + 1)).coerceIn(0, 2) + 1
