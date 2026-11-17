package dev.bold7.launcher.led

import dev.bold7.launcher.profiles.Profile

// The 9900's LED (docs/BBOS7-VISUEL.md §41), pure: what Bold7 knows in, the colour bold7-led
// flashes out (system/module/led). Tested on the JVM.

/** The global setting bold7-led reads, and its values (led/model.c parses the same). */
const val LED_SETTING = "bold7_led"

enum class LedColour(val value: String) { OFF("off"), RED("red"), AMBER("amber"), GREEN("green") }

data class LedInputs(
    /** Something new in the Hub: the splat of Messages (HubBadges.hub). */
    val newInHub: Boolean,
    /** The LED of Messages and Email's notifier in Options (notification_light_pulse). */
    val messageLed: Boolean,
    /** The sound profile in use (bold7_profile), null when unknown. */
    val profile: Profile?,
    val ringing: Boolean,
    /** Android's own "battery low" (BatteryManager.EXTRA_BATTERY_LOW). */
    val batteryLow: Boolean,
    val plugged: Boolean,
    val inService: Boolean,
    /** Screen Display's LED Coverage Indicator (bold7_led_coverage). */
    val coverageLed: Boolean,
)

/**
 * One colour at a time (guide 7.1 p. 15, M). Red: a new message when the profile's notifier asks
 * for the LED (M; sim-36, C), an incoming call (simulator log, C: red while it rings, the green of
 * the coverage back after). All Alerts Off shows neither, Phone Calls Only no message (D, as their
 * sounds). Amber: a low battery, unplugged, which no setting turns off (M); its threshold is
 * Android's (D). Green: in coverage with the LED Coverage Indicator (M, C). Red before green (C),
 * amber between (D). Blue (Bluetooth) is not done: its setting's default is not sourced.
 */
fun ledColour(i: LedInputs): LedColour = when {
    i.profile != Profile.ALERTS_OFF && (i.ringing || (i.newInHub && i.messageLed && i.profile != Profile.PHONE_ONLY)) -> LedColour.RED
    i.batteryLow && !i.plugged -> LedColour.AMBER
    i.inService && i.coverageLed -> LedColour.GREEN
    else -> LedColour.OFF
}
