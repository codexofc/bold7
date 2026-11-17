package dev.bold7.clock

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

// When Charging (Clock Options, sim-170, sim-417): the 9900 opens the Clock as soon as the charger is
// in, on the home screen as over the lock screen (sim-412, sim-414). Android sends no app the
// charger's broadcast unless it runs: the home of Bold7, which always does, starts the Clock by
// ACTION_CHARGING (dev.bold7.launcher's ChargingClock), and the Clock leaves exactly one of its two
// aliases enabled for it, or none for Do Nothing, so the launcher needs to know nothing of its options.

/** The action the launcher starts on the charger; both aliases answer it, behind CHARGING_PERMISSION. */
const val ACTION_CHARGING = "dev.bold7.clock.CHARGING"
const val CHARGING_CLOCK = "dev.bold7.clock.ChargingClock"
const val CHARGING_BEDSIDE = "dev.bold7.clock.ChargingBedside"

/** The alias left enabled for [c], null for Do Nothing. */
fun chargingAlias(c: WhenCharging): String? = when (c) {
    WhenCharging.NOTHING -> null
    WhenCharging.CLOCK -> CHARGING_CLOCK
    WhenCharging.BEDSIDE -> CHARGING_BEDSIDE
}

/** The page the charger opens through [alias]: Enter Bedside Mode, or Display Clock. */
fun chargingPage(alias: String?): Page = if (alias == CHARGING_BEDSIDE) Page.BEDSIDE else Page.CLOCK

/** Both aliases set for [c] (binder: off the main thread). The app is not killed. */
fun applyCharging(context: Context, c: WhenCharging) {
    val on = chargingAlias(c)
    for (alias in listOf(CHARGING_CLOCK, CHARGING_BEDSIDE)) {
        val state = if (alias == on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(ComponentName(context, alias), state, PackageManager.DONT_KILL_APP)
    }
}
