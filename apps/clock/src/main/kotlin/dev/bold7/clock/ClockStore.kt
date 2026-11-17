package dev.bold7.clock

import android.content.Context
import android.content.SharedPreferences

/**
 * What the Clock keeps, in the device protected storage (/data/user_de/0/dev.bold7.clock/
 * shared_prefs/clock.xml): readable before the first unlock, so that an alarm armed again at
 * LOCKED_BOOT_COMPLETED rings even if nobody unlocks the phone after a night reboot; read by
 * tools/uninstall.sh in root to say which alarm leaves with the app. Written with commit(), on the
 * io thread only: an alarm must not be lost to a write still in the air.
 */
class ClockStore(context: Context) : ClockKeep {
    private val prefs: SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences("clock", Context.MODE_PRIVATE)

    override var settings: ClockSettings
        get() = ClockSettings.read { prefs.getString(it, null) }
        set(value) {
            prefs.edit().apply { value.write().forEach { (k, v) -> putString(k, v) } }.commit()
        }

    /** A snooze's ring (wall clock ms), or null. */
    override var snoozeUntil: Long? by long("snooze_until")

    /** The ring AlarmManager holds now (wall clock ms), or null: what a reboot may have missed. */
    var armedFor: Long? by long("armed_for")

    /** The alarm stream's volume before a ring set its own, to give back (also after a crash, at boot). */
    var savedVolume: Long? by long("saved_volume")

    /** The countdown timer: its duration, what is left stopped, its end while running (elapsed realtime ms). */
    override var timer: Countdown
        get() = Countdown(prefs.getLong("timer_duration", DEFAULT_TIMER_MS), prefs.getLong("timer_remaining", DEFAULT_TIMER_MS), prefs.getLong("timer_ends", -1).takeIf { it >= 0 })
        set(value) {
            prefs.edit().putLong("timer_duration", value.duration).putLong("timer_remaining", value.remaining).putLong("timer_ends", value.endsAt ?: -1).commit()
        }

    /** The stopwatch, for the time the Clock is closed (elapsed realtime ms). */
    override var stopwatch: Stopwatch
        get() = Stopwatch(
            prefs.getLong("watch_base", 0), prefs.getLong("watch_started", -1).takeIf { it >= 0 },
            prefs.getString("watch_laps", "").orEmpty().split(',').mapNotNull(String::toLongOrNull),
        )
        set(value) {
            prefs.edit().putLong("watch_base", value.base).putLong("watch_started", value.startedAt ?: -1).putString("watch_laps", value.laps.joinToString(",")).commit()
        }

    private fun long(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, Long?> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null

        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Long?) {
            prefs.edit().apply { if (value == null) remove(key) else putLong(key, value) }.commit()
        }
    }
}
