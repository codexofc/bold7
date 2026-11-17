package dev.bold7.sysui

import dev.bold7.design.ChargingBolt
import dev.bold7.design.Strips

// Which indicators of the 9900's banner show, from what Android says. Pure.
// Images: one frame each of the strips of net_rim_theme_BlackBerry7_640x480_b, sliced into
// banner_* drawables by tools/import-assets.sh bandeau. Sources in docs/BBOS7-VISUEL.md §18.

enum class Radio { IN_SERVICE, NO_SERVICE, EMERGENCY_ONLY, AIRPLANE }

data class RadioState(
    /** dBm of the Wi-Fi network, null without one. */
    val wifiRssi: Int? = null,
    val radio: Radio = Radio.NO_SERVICE,
    /** SignalStrength.getLevel(), 0 to 4. */
    val signalLevel: Int = 0,
    /** TelephonyManager.NETWORK_TYPE_* of the data connection. */
    val networkType: Int = 0,
    val dataConnected: Boolean = false,
    val roaming: Boolean = false,
    /** TelephonyManager.getSimOperatorName(), the carrier's own name (SPN). */
    val simOperator: String? = null,
    /** TelephonyManager.getNetworkOperatorName(), the network's name, when the SIM gives none. */
    val networkOperator: String? = null,
)

/**
 * The network's name in the middle of the banner's first line, only in service, as Android's own
 * OperatorNameView (hidden out of service and in airplane mode): the SIM's name, else the network's.
 */
fun operatorName(s: RadioState): String? =
    if (s.radio != Radio.IN_SERVICE) null else s.simOperator?.takeIf { it.isNotBlank() } ?: s.networkOperator?.takeIf { it.isNotBlank() }

/** Wi-Fi bars 0 to 4, by the 2.4 GHz RSSI thresholds of Android's WifiManager. */
fun wifiLevel(rssi: Int): Int = listOf(-88, -77, -66, -55).count { rssi >= it }

/** net_rim_Ribbon_WlanSignalLevel (30x24), frames 1 to 5 for a connected network (Strips), null without one. */
fun wifiImage(rssi: Int?): String? = rssi?.let { "banner_wifi_${Strips.wifiColumn(wifiLevel(it))}" }

/**
 * net_rim_Ribbon_SignalLevel (49x19), by the rule the home's banner shares (Strips): OFF with the
 * radio turned off, X without coverage, SOS for emergency calls only, the bars in service.
 */
fun signalImage(s: RadioState): String =
    when (val column = Strips.signalColumn(s.signalLevel, s.radio == Radio.IN_SERVICE, s.radio == Radio.AIRPLANE, s.radio == Radio.EMERGENCY_ONLY)) {
        0 -> "banner_signal_off"
        1 -> "banner_signal_x"
        8 -> "banner_signal_sos"
        else -> "banner_signal_${column - 2}"
    }

/**
 * The coverage at the left of the bars, BBAlpha Sans Condensed (Coverage in banner.pme): the
 * guide's names (3G, 4G, H+, EDGE, GPRS, p. 262), in capitals with a data connection, in lowercase
 * without ("edge" on the simulator, which has none: sim-43; "EDGE", "3G" on gsm 019 and 022).
 * Nothing out of service, nor for a type without data (GSM alone, CDMA: not the Q25's).
 */
fun coverageText(s: RadioState): String? {
    if (s.radio != Radio.IN_SERVICE) return null
    val name = when (s.networkType) {
        1 -> "GPRS"
        2 -> "EDGE"
        3, 8, 9, 10, 17 -> "3G" // UMTS, HSDPA, HSUPA, HSPA, TD_SCDMA
        15 -> "H+" // HSPAP
        // Simplification: the 9900 has no LTE; "4G" is the guide's name for the fastest network (D).
        13, 20 -> "4G" // LTE, NR
        else -> return null
    }
    return if (s.dataConnected) name else name.lowercase()
}

/** net_rim_Ribbon_Roaming (28x28), its solid white triangle (D: the guide only says "triangular"), before the network's name. */
fun roamingImage(s: RadioState): String? = if (s.roaming && s.radio == Radio.IN_SERVICE) "banner_roaming" else null

/**
 * net_rim_Indicator_BatteryLevel (50x25), by the rule the home's banner shares (Strips): its column
 * by tenths, "?" (11) for an unknown level; [plugged], the row with the plug.
 */
fun batteryImage(level: Int, plugged: Boolean): String {
    val column = Strips.batteryColumn(level)
    return if (plugged) "banner_battery_charging_$column" else "banner_battery_$column"
}

private fun percent(raw: Int, scale: Int) = if (raw < 0) -1 else raw * 100 / scale.coerceAtLeast(1)

/**
 * The same from ACTION_BATTERY_CHANGED's extras ([raw] level on [scale], BatteryManager's [status]):
 * the plug when full on power, as the home's banner (Strips.batteryRow, BannerSource).
 */
fun batteryImage(raw: Int, scale: Int, status: Int): String {
    val level = percent(raw, scale)
    return batteryImage(level, plugged = Strips.batteryRow(status, level) == Strips.BATTERY_PLUGGED_ROW)
}

/** Whether the bolt animates over the battery (ChargingBolt), from the same extras. */
fun charging(raw: Int, scale: Int, status: Int): Boolean = ChargingBolt.shows(status, percent(raw, scale))

/** lightning_bolt_01 to _05 (18x29) for the bolt's image 1 to 5, none for 0. */
fun boltImage(image: Int): String? = if (image in 1..5) "banner_bolt_$image" else null

/** Slots of Android's own icons that the banner's radio indicators replace (framework strings status_bar_*). */
val RADIO_SLOTS = setOf("wifi", "mobile", "airplane")

/**
 * Slots of Android's the 9900's banner has nothing for (docs/BBOS7-VISUEL.md §18):
 * the input method's icon (an IME's showStatusIcon; OS 7 names the input language elsewhere, its
 * *_input.gif, not in the banner), "secure" (nothing sets it in Android 14), CDMA's roaming
 * indicator and EVDO bars and the old data and signal slots (the banner's roaming triangle,
 * coverage and bars stand for them), MediaTek's eMBMS broadcast.
 */
val UNSHOWN_SLOTS = setOf("ime", "secure", "cdma_eri", "data_connection", "phone_evdo_signal", "phone_signal", "embms")

/**
 * Whether one of Android's status icons shows: its own visibility, except for the radio ones and
 * [UNSHOWN_SLOTS] in the two bars that carry the 9900's indicators. SystemUI's block list cannot do
 * it on this ROM: it skips the Wi-Fi and mobile views, and MediaTek's StatusIconContainer always
 * shows "mobile"; for the others, the same rule in one place, and the shade's header keeps them.
 */
fun androidIconShown(slot: String?, visible: Boolean, inDressedBar: Boolean): Boolean =
    visible && !(inDressedBar && (slot in RADIO_SLOTS || slot in UNSHOWN_SLOTS))
