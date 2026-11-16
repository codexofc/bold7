package dev.bold7.clock

import dev.bold7.ui.Spin
import dev.bold7.ui.SpinColumn

// The values of the Set Alarm and Set Timer boxes of OS 7.1's Clock (sim-156 to sim-161, C): the
// hour and the minute, each a list that wraps, and Off, On or Weekdays, then OK and Cancel. The box
// is core/ui's [Spin] (SpinDialog draws and keys it): only its columns are here.
// Tested on the JVM (SetAlarmTest).

/** What the box edits: the alarm's fields, or the timer's with its [second] (the third field then counts seconds). */
data class AlarmFields(val hour: Int, val minute: Int, val repeat: AlarmRepeat, val second: Int? = null) {
    val alarm: Alarm get() = Alarm(hour, minute, repeat)

    /** The timer's duration, ms. */
    val duration: Long get() = ((hour * 60L + minute) * 60 + (second ?: 0)) * 1000
}

object SetAlarm {
    /** The timer seconds field counts 00 to 99 (sim-329). */
    const val SECONDS = 100

    private val hour = SpinColumn<AlarmFields>({ "%02d".format(it.hour) }, { v, by -> v.copy(hour = (v.hour + by).mod(24)) })
    private val minute = SpinColumn<AlarmFields>({ "%02d".format(it.minute) }, { v, by -> v.copy(minute = (v.minute + by).mod(60)) }, before = ":")

    /**
     * Set Alarm, opened on the repeat field as the 9900 did (sim-156); [repeatLabels] are On,
     * Weekdays and Off in capitals (sim-158), in [AlarmRepeat]'s order. The list does not wrap, and
     * at an end a step leaves the value, so no neighbour is drawn there.
     */
    fun open(alarm: Alarm, repeatLabels: List<String>): Spin<AlarmFields> = Spin(
        AlarmFields(alarm.hour, alarm.minute, alarm.repeat),
        listOf(
            hour, minute,
            SpinColumn({ repeatLabels[it.repeat.ordinal] }, { v, by -> v.copy(repeat = AlarmRepeat.entries[(v.repeat.ordinal + by).coerceIn(0, AlarmRepeat.entries.size - 1)]) }),
        ),
        focus = 2, back = 2,
    )

    /**
     * Set Timer (guide p. 204, B): the same box with hours, minutes and seconds, the third field
     * the seconds wrapping at 99 (sim-329, C: "99" above "00"), opened on them (sim-329).
     */
    fun timer(ms: Long): Spin<AlarmFields> {
        val s = ms / 1000
        return Spin(
            AlarmFields((s / 3600).toInt() % 24, (s / 60 % 60).toInt(), AlarmRepeat.OFF, (s % 60).toInt()),
            listOf(hour, minute, SpinColumn({ "%02d".format(it.second) }, { v, by -> v.copy(second = ((v.second ?: 0) + by).mod(SECONDS)) }, before = ":")),
            focus = 2,
        )
    }
}
