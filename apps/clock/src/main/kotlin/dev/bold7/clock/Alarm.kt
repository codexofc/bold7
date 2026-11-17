package dev.bold7.clock

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil

// The alarm of OS 7.1's Clock, pure: one daily alarm, Off, On or
// Weekdays (Set Alarm, sim-156 to sim-158, C; guide 7.1 p. 202, B), its options (Clock Options >
// Alarm, sim-171, sim-174, sim-175, C), when it rings next. Tested on the JVM (AlarmTest).

/** Set Alarm's choice, in the order of its list (sim-158): every day, Monday to Friday, never. */
enum class AlarmRepeat { ON, WEEKDAYS, OFF }

/** The alarm's time, in the phone's time zone, and its repeat. 8:00 Off is the 9900's own (sim-156). */
data class Alarm(val hour: Int = 8, val minute: Int = 0, val repeat: AlarmRepeat = AlarmRepeat.OFF)

/** Options > Volume: Silent (0), 1 to 10, or Escalating (sim-174, sim-177). */
const val SILENT = 0
const val ESCALATING = 11

/** Options > Snooze Time, minutes, None being 0 (sim-175). */
val SNOOZE_CHOICES = listOf(0, 1, 5, 10, 15, 30)

/**
 * Clock Options > Alarm (sim-171, C): the tone (a file of the module's /product/media/audio/alarms,
 * system/module/sounds.tsv), its volume, the snooze in minutes (0: Dismiss only), whether it
 * vibrates. The 9900's defaults: Evolving Dream, 8, 5 Min., Off.
 */
data class AlarmOptions(
    val tone: String = DEFAULT_TONE,
    val volume: Int = 8,
    val snooze: Int = 5,
    val vibrate: Boolean = false,
)

/** Evolving Dream (sim-171, C). */
const val DEFAULT_TONE = "Dream.m4a"

/**
 * Clock Options > Clock Face (sim-330, C: Analogue, Digital, Flip Clock, LCD Digital, in that
 * order). Only LCD Digital has images in the firmware (A, lcddigital); Digital and Flip Clock are
 * drawn text on black and on two cards (sim-331, sim-332).
 */
enum class ClockFace { ANALOGUE, DIGITAL, FLIP, LCD }

/**
 * Clock Options > Countdown Timer (sim-173, C): the timer's tone, volume and vibration, the same
 * fields as the alarm's. The 9900's defaults: 3 Beeps, 8, Off.
 */
data class TimerOptions(val tone: String = TIMER_TONE, val volume: Int = 8, val vibrate: Boolean = false)

/** Clock Options > When Charging (sim-417, C: Do Nothing, Display Clock, Enter Bedside Mode, in that order). */
enum class WhenCharging { NOTHING, CLOCK, BEDSIDE }

/**
 * What the Clock shows (Clock Options): its [face]; a second time zone, [homeZone] (Home Time Zone,
 * sim-170; guide p. 201), null for the phone's own, which shows nothing more; what the charger
 * opens, [charging] (When Charging, sim-170: Display Clock), and whether pulling it out closes the
 * Clock again, [dismiss] (Dismiss Clock Automatically, checked: sim-170, sim-413); and Bedside
 * Mode's Dim Screen [dim] (sim-172, checked by default).
 */
data class DisplayOptions(
    val face: ClockFace = ClockFace.ANALOGUE,
    val homeZone: String? = null,
    val dim: Boolean = true,
    val charging: WhenCharging = WhenCharging.CLOCK,
    val dismiss: Boolean = true,
)

/** What the Clock keeps (device protected storage, readable before the first unlock). */
data class ClockSettings(
    val alarm: Alarm = Alarm(),
    val options: AlarmOptions = AlarmOptions(),
    val timer: TimerOptions = TimerOptions(),
    val display: DisplayOptions = DisplayOptions(),
) {
    /** As strings, the keys of SharedPreferences. */
    fun write(): Map<String, String> = mapOf(
        "hour" to "${alarm.hour}", "minute" to "${alarm.minute}", "repeat" to alarm.repeat.name,
        "tone" to options.tone, "volume" to "${options.volume}", "snooze" to "${options.snooze}",
        "vibrate" to "${options.vibrate}",
        "timer_tone" to timer.tone, "timer_volume" to "${timer.volume}", "timer_vibrate" to "${timer.vibrate}",
        "face" to display.face.name, "home_zone" to (display.homeZone ?: ""), "dim" to "${display.dim}",
        "when_charging" to display.charging.name, "dismiss_clock" to "${display.dismiss}",
    )

    companion object {
        /** Each value read back, or its default when it is missing or broken: one bad key never loses the others. */
        fun read(get: (String) -> String?): ClockSettings {
            val d = ClockSettings()
            return ClockSettings(
                Alarm(
                    get("hour")?.toIntOrNull()?.takeIf { it in 0..23 } ?: d.alarm.hour,
                    get("minute")?.toIntOrNull()?.takeIf { it in 0..59 } ?: d.alarm.minute,
                    get("repeat")?.let { r -> AlarmRepeat.entries.find { it.name == r } } ?: d.alarm.repeat,
                ),
                AlarmOptions(
                    get("tone")?.takeIf { it.isNotEmpty() && '/' !in it } ?: d.options.tone,
                    get("volume")?.toIntOrNull()?.takeIf { it in SILENT..ESCALATING } ?: d.options.volume,
                    get("snooze")?.toIntOrNull()?.takeIf { it in SNOOZE_CHOICES } ?: d.options.snooze,
                    get("vibrate")?.toBooleanStrictOrNull() ?: d.options.vibrate,
                ),
                TimerOptions(
                    get("timer_tone")?.takeIf { it.isNotEmpty() && '/' !in it } ?: d.timer.tone,
                    get("timer_volume")?.toIntOrNull()?.takeIf { it in SILENT..ESCALATING } ?: d.timer.volume,
                    get("timer_vibrate")?.toBooleanStrictOrNull() ?: d.timer.vibrate,
                ),
                DisplayOptions(
                    get("face")?.let { f -> ClockFace.entries.find { it.name == f } } ?: d.display.face,
                    get("home_zone")?.takeIf { z -> z.isNotEmpty() && runCatching { ZoneId.of(z) }.isSuccess },
                    get("dim")?.toBooleanStrictOrNull() ?: d.display.dim,
                    get("when_charging")?.let { c -> WhenCharging.entries.find { it.name == c } } ?: d.display.charging,
                    get("dismiss_clock")?.toBooleanStrictOrNull() ?: d.display.dismiss,
                ),
            )
        }
    }
}

/** What a ring plays and does: its [tone], [volume] (Options' scale), whether it [vibrate]s, and the [snooze] minutes (0: Dismiss only). */
data class RingSpec(val tone: String, val volume: Int, val vibrate: Boolean, val snooze: Int)

/**
 * The ring of the alarm (Clock Options > Alarm) or of the timer (Countdown Timer, which has no snooze:
 * guide p. 204, Dismiss alone, D). RingService reads it at each ring, so a change of Options holds from the next.
 */
fun ringSpec(timer: Boolean, s: ClockSettings): RingSpec =
    if (timer) RingSpec(s.timer.tone, s.timer.volume, s.timer.vibrate, 0)
    else RingSpec(s.options.tone, s.options.volume, s.options.vibrate, s.options.snooze)

/**
 * When the alarm rings next, strictly after [now] (epoch ms), or null: the sooner of a pending
 * snooze ([snoozeUntil], forgotten once past) and the alarm's next day. The time is the wall clock's
 * in [now]'s zone, so a change of time zone or of summer time needs a new call (BootReceiver does
 * it on TIME_SET and TIMEZONE_CHANGED); in a summer time gap, Java moves it forward by the gap.
 */
fun nextRing(alarm: Alarm, snoozeUntil: Long?, now: ZonedDateTime): Long? {
    val nowMs = now.toInstant().toEpochMilli()
    val snooze = snoozeUntil?.takeIf { it > nowMs }
    val regular = if (alarm.repeat == AlarmRepeat.OFF) null else {
        val time = LocalTime.of(alarm.hour, alarm.minute)
        generateSequence(now.toLocalDate()) { it.plusDays(1) }
            .map { ZonedDateTime.of(it, time, now.zone) }
            .first { it.isAfter(now) && (alarm.repeat == AlarmRepeat.ON || it.dayOfWeek !in WEEKEND) }
            .toInstant().toEpochMilli()
    }
    return listOfNotNull(snooze, regular).minOrNull()
}

private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** A ring Android could not deliver (the phone off or rebooting at that moment) still rings at boot, for this long after (D). */
const val MISSED_WINDOW_MS = 15 * 60_000L

/** Whether the ring armed for [armedFor] was missed recently enough to ring now (BootReceiver). */
fun missed(armedFor: Long?, now: Long): Boolean = armedFor != null && armedFor <= now && now - armedFor < MISSED_WINDOW_MS

fun snoozeUntil(now: Long, minutes: Int): Long = now + minutes * 60_000L

/**
 * The alarm stream's index while it rings, of [max]: [volume] tenths of it, rounded up, full for
 * Escalating (its rise is the player's [gain]); Silent is 0 (D: the 9900 offers it, sim-177).
 */
fun streamLevel(volume: Int, max: Int): Int = when (volume) {
    SILENT -> 0
    ESCALATING -> max
    else -> ceil(volume.coerceIn(1, 10) * max / 10.0).toInt().coerceIn(1, max)
}

/** Escalating rises from a tenth to full in this long (D: OS 7's ramp is not sourced). */
const val ESCALATE_MS = 60_000L

/** The player's gain [elapsed] ms into the ring: full, or Escalating's linear rise. */
fun gain(volume: Int, elapsed: Long): Float =
    if (volume != ESCALATING) 1f else (0.1f + 0.9f * elapsed.coerceIn(0, ESCALATE_MS) / ESCALATE_MS)

/** 3 Beeps, the timer's (sim-173, C). */
const val TIMER_TONE = "3Beeps.m4a"
