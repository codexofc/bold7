package dev.bold7.ui

import java.time.LocalDate
import java.time.LocalTime

// OS 7's date and time boxes, pure (the Calendar's form and Go to Date, sim-234, sim-235, sim-248;
// Tasks' Due, sim-274, sim-275; the box of the Clock's Set Alarm, sim-156): a strip of columns, the
// focused one open on its neighbours, OK and Cancel. Tested on the JVM (SpinTest).

/** A column of a box: its text for a value, the value one or more steps away, and what the strip writes before it (sim-274: "17 : 00"). */
class SpinColumn<T>(val label: (T) -> String, val turn: (T, Int) -> T, val before: String = "")

enum class SpinKey { UP, DOWN, LEFT, RIGHT, CLICK, ENTER, BACK }

sealed interface SpinResult<out T> {
    data class Show<T>(val spin: Spin<T>) : SpinResult<T>
    data class Save<T>(val value: T) : SpinResult<T>
    data object Cancel : SpinResult<Nothing>
}

/**
 * A box: [value], its [columns], the focus on a column (0 until columns.size), on OK
 * ([ok]) or on Cancel ([cancel]); [back], the column the buttons go up to.
 */
data class Spin<T>(val value: T, val columns: List<SpinColumn<T>>, val focus: Int = 0, val back: Int = 0) {
    val ok: Int get() = columns.size
    val cancel: Int get() = columns.size + 1
    val onButtons: Boolean get() = focus >= ok

    fun key(key: SpinKey): SpinResult<T> = when (key) {
        SpinKey.ENTER -> SpinResult.Save(value)
        SpinKey.BACK -> SpinResult.Cancel
        // As Set Alarm (D there too): a click on a column sends the focus to OK.
        SpinKey.CLICK -> when (focus) {
            ok -> SpinResult.Save(value)
            cancel -> SpinResult.Cancel
            else -> SpinResult.Show(copy(focus = ok, back = focus))
        }
        SpinKey.LEFT -> SpinResult.Show(copy(focus = if (onButtons) ok else (focus - 1).coerceAtLeast(0)))
        SpinKey.RIGHT -> SpinResult.Show(copy(focus = if (onButtons) cancel else (focus + 1).coerceAtMost(columns.size - 1)))
        SpinKey.UP, SpinKey.DOWN -> SpinResult.Show(turn(if (key == SpinKey.DOWN) 1 else -1))
    }

    /** One step of the focused column; on the buttons, up goes back to the column, down stays. */
    fun turn(by: Int): Spin<T> = if (onButtons) (if (by < 0) copy(focus = back) else this) else copy(value = columns[focus].turn(value, by))

    companion object {
        /**
         * Go to Date and the form's dates (sim-248): the weekday (a step is a day), the day, the
         * month and the year; focus on the weekday as the simulator opened it.
         */
        fun date(d: LocalDate): Spin<LocalDate> = Spin(
            d,
            listOf(
                SpinColumn({ it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) }, { v, by -> v.plusDays(by.toLong()) }),
                SpinColumn({ "${it.dayOfMonth}" }, { v, by -> v.withDayOfMonth((v.dayOfMonth - 1 + by).mod(v.lengthOfMonth()) + 1) }),
                SpinColumn({ it.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) }, { v, by -> v.plusMonths(by.toLong()) }),
                SpinColumn({ "${it.year}" }, { v, by -> v.plusYears(by.toLong()) }),
            ),
        )

        /**
         * The form's times (sim-234, sim-235, sim-274): the hour, wrapping, then ":" and the minute
         * by 5 (sim-235: 00, 05, 10), a minute between two steps first moved onto one; opened on
         * the hour.
         */
        fun time(t: LocalTime): Spin<LocalTime> = Spin(
            t.withSecond(0).withNano(0),
            listOf(
                SpinColumn({ "%02d".format(it.hour) }, { v, by -> v.withHour((v.hour + by).mod(24)) }),
                SpinColumn({ "%02d".format(it.minute) }, { v, by -> v.withMinute(((v.minute / 5 + by) * 5).mod(60).let { m -> if (v.minute % 5 != 0 && by < 0) (m + 5).mod(60) else m }) }, before = ":"),
            ),
        )
    }
}
