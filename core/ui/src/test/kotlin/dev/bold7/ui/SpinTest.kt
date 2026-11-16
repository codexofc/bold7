package dev.bold7.ui

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class SpinTest {
    private fun <T> Spin<T>.k(key: SpinKey): Spin<T> = (key(key) as SpinResult.Show<T>).spin

    @Test fun minutesGoByFiveAndHoursWrap() {
        // sim-235: 00, 05, 10.
        val t = Spin.time(LocalTime.of(18, 0))
        assertEquals(LocalTime.of(18, 5), t.k(SpinKey.RIGHT).k(SpinKey.DOWN).value)
        assertEquals(LocalTime.of(18, 55), t.k(SpinKey.RIGHT).k(SpinKey.UP).value)
        assertEquals(LocalTime.of(0, 0), Spin.time(LocalTime.of(23, 0)).k(SpinKey.DOWN).value)
        assertEquals(listOf("18", "00"), t.columns.map { it.label(t.value) })
        // sim-274: "17 : 00", the colon in the strip before the minutes.
        assertEquals(listOf("", ":"), t.columns.map { it.before })
        // A minute between two steps lands on one.
        assertEquals(LocalTime.of(18, 20), Spin.time(LocalTime.of(18, 17)).k(SpinKey.RIGHT).k(SpinKey.DOWN).value)
        assertEquals(LocalTime.of(18, 15), Spin.time(LocalTime.of(18, 17)).k(SpinKey.RIGHT).k(SpinKey.UP).value)
    }

    @Test fun theDateBoxHasTheWeekdayDayMonthAndYear() {
        // sim-248: Fri 13 Nov 2026, the weekday under the focus.
        val d = Spin.date(LocalDate.of(2026, 11, 13))
        assertEquals(listOf("Fri", "13", "Nov", "2026"), d.columns.map { it.label(d.value) })
        assertEquals(LocalDate.of(2026, 11, 14), d.k(SpinKey.DOWN).value)
        assertEquals(LocalDate.of(2026, 11, 1), Spin.date(LocalDate.of(2026, 11, 30)).k(SpinKey.RIGHT).k(SpinKey.DOWN).value)
        assertEquals(LocalDate.of(2026, 12, 13), d.k(SpinKey.RIGHT).k(SpinKey.RIGHT).k(SpinKey.DOWN).value)
        assertEquals(LocalDate.of(2027, 11, 13), d.k(SpinKey.RIGHT).k(SpinKey.RIGHT).k(SpinKey.RIGHT).k(SpinKey.DOWN).value)
    }

    @Test fun theButtonsAsSetAlarm() {
        val t = Spin.time(LocalTime.of(18, 0)).k(SpinKey.RIGHT)
        val onOk = t.k(SpinKey.CLICK)
        assertEquals(t.ok, onOk.focus)
        assertEquals(t.cancel, onOk.k(SpinKey.RIGHT).focus)
        assertEquals(1, onOk.k(SpinKey.UP).focus) // back to the minutes
        assertEquals(SpinResult.Save(LocalTime.of(18, 0)), onOk.key(SpinKey.CLICK))
        assertEquals(SpinResult.Cancel, onOk.k(SpinKey.RIGHT).key(SpinKey.CLICK))
        assertEquals(SpinResult.Save(LocalTime.of(18, 0)), t.key(SpinKey.ENTER))
        assertEquals(SpinResult.Cancel, t.key(SpinKey.BACK))
    }
}
