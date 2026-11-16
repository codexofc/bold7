package dev.bold7.design

/**
 * The status indicators of the 9900's banners, the home's (launcher, docs/BBOS7-VISUEL.md §1) and
 * the apps' (SystemUI, §18): which frame of the theme's strips shows, one rule for both. Columns of
 * the strips, counted from 0.
 */
object Strips {
    /**
     * net_rim_Indicator_BatteryLevel-icons50x25.png (A): 12 columns, fills of 0 to 40 px by steps of
     * 4 (red at the first, orange at the second), then "?". The tenth rounded up (D): 1 to 10 % is
     * the red step. Unknown ([percent] < 0): the "?".
     */
    fun batteryColumn(percent: Int): Int = if (percent < 0) 11 else ((percent + 9) / 10).coerceIn(0, 10)

    /** The strip's third row, with the plug: on external power (its first two rows are the same). */
    const val BATTERY_PLUGGED_ROW = 2

    /**
     * net_rim_Ribbon_SignalLevel-icons49x19.png (A): OFF (radio turned off), X (no coverage), 0 to 5
     * bars, SOS (emergency calls only), as the guide names them (p. 262, B). Android's level 0 to 4
     * on the bars (D): none for 0, then 2 to 5, so a usable signal never looks like none.
     */
    fun signalColumn(level: Int, inService: Boolean, radioOff: Boolean, emergencyOnly: Boolean = false): Int = when {
        radioOff -> 0
        emergencyOnly -> 8
        !inService -> 1
        else -> 2 + if (level <= 0) 0 else (level + 1).coerceAtMost(5)
    }

    /** net_rim_Ribbon_WlanSignalLevel-icons30x24.png (A): an empty frame, then Android's levels 0 to 4. */
    fun wifiColumn(level: Int): Int = level.coerceIn(0, 4) + 1
}
