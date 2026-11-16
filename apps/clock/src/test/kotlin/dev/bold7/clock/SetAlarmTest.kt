package dev.bold7.clock

import dev.bold7.ui.Spin
import dev.bold7.ui.SpinKey
import dev.bold7.ui.SpinResult
import kotlin.test.Test
import kotlin.test.assertEquals

// Set Alarm and Set Timer are core/ui's Spin (SpinDialog), filled here: what these tests keep is
// what the 9900's box did (sim-156 to sim-161), now through the shared reducer.
class SetAlarmTest {
    private val repeats = listOf("ON", "WEEKDAYS", "OFF")
    private val opened = SetAlarm.open(Alarm(8, 0, AlarmRepeat.OFF), repeats)

    private fun Spin<AlarmFields>.k(key: SpinKey): Spin<AlarmFields> = (key(key) as SpinResult.Show).spin

    @Test fun opensOnTheRepeatFieldAsThe9900() {
        // sim-156: the list of Off, On, Weekdays open under the trackpad.
        assertEquals(2, opened.focus)
        assertEquals(listOf("08", "00", "OFF"), opened.columns.map { it.label(opened.value) })
        assertEquals(listOf("", ":", ""), opened.columns.map { it.before })
    }

    @Test fun upAndDownTurnTheFocusedFieldWithoutWrappingTheRepeat() {
        // sim-157, sim-158: On, Weekdays, Off, top to bottom, without wrapping.
        val up = opened.k(SpinKey.UP)
        assertEquals(AlarmRepeat.WEEKDAYS, up.value.repeat)
        assertEquals(AlarmRepeat.ON, up.k(SpinKey.UP).value.repeat)
        assertEquals(AlarmRepeat.ON, up.k(SpinKey.UP).k(SpinKey.UP).value.repeat)
        assertEquals(AlarmRepeat.OFF, opened.k(SpinKey.DOWN).value.repeat)
        // At an end the neighbour is the value itself: the dialog draws nothing there.
        val column = opened.columns[2]
        assertEquals(opened.value, column.turn(opened.value, 1))
    }

    @Test fun hoursAndMinutesWrapAndDownCountsUp() {
        // sim-159, sim-160: 59 above 00, 01 under it; a step down goes to 01.
        val minute = opened.k(SpinKey.LEFT)
        assertEquals(1, minute.k(SpinKey.DOWN).value.minute)
        assertEquals(59, minute.k(SpinKey.UP).value.minute)
        val hour = SetAlarm.open(Alarm(23, 0, AlarmRepeat.OFF), repeats).k(SpinKey.LEFT).k(SpinKey.LEFT)
        assertEquals(0, hour.k(SpinKey.DOWN).value.hour)
    }

    @Test fun leftAndRightGoFromFieldToFieldAndStopAtTheEnds() {
        val minute = opened.k(SpinKey.LEFT)
        assertEquals(1, minute.focus)
        val hour = minute.k(SpinKey.LEFT)
        assertEquals(0, hour.k(SpinKey.LEFT).focus)
        assertEquals(2, opened.k(SpinKey.RIGHT).focus)
    }

    @Test fun aClickOnAFieldGoesToOkThenOkSavesAndCancelDropsIt() {
        val ok = opened.k(SpinKey.CLICK)
        assertEquals(opened.ok, ok.focus)
        assertEquals(Alarm(8, 0, AlarmRepeat.OFF), (ok.key(SpinKey.CLICK) as SpinResult.Save).value.alarm)
        val cancel = ok.k(SpinKey.RIGHT)
        assertEquals(opened.cancel, cancel.focus)
        assertEquals(SpinResult.Cancel, cancel.key(SpinKey.CLICK))
        // Up from the buttons goes back to the field the click left.
        assertEquals(2, ok.k(SpinKey.UP).focus)
    }

    @Test fun enterSavesFromAnywhereAndEscapeCancels() {
        // sim-161: Enter on the minutes' list saved 16:14 On.
        val set = SetAlarm.open(Alarm(16, 14, AlarmRepeat.ON), repeats).k(SpinKey.LEFT)
        assertEquals(Alarm(16, 14, AlarmRepeat.ON), (set.key(SpinKey.ENTER) as SpinResult.Save).value.alarm)
        assertEquals(SpinResult.Cancel, set.key(SpinKey.BACK))
    }

    @Test fun setTimerHasSecondsInThePlaceOfTheRepeat() {
        val t = SetAlarm.timer(90_000)
        assertEquals(Triple(0, 1, 30), Triple(t.value.hour, t.value.minute, t.value.second))
        assertEquals(2, t.focus) // sim-329: the box opens on the seconds
        assertEquals(listOf("", ":", ":"), t.columns.map { it.before })
        assertEquals(29, t.k(SpinKey.UP).value.second)
        // sim-329: "99" above "00", so the seconds count to 99 and wrap there
        assertEquals(99, SetAlarm.timer(0).k(SpinKey.UP).value.second)
        assertEquals(0, t.copy(value = t.value.copy(second = 99)).k(SpinKey.DOWN).value.second)
        assertEquals(100_000L, AlarmFields(0, 0, AlarmRepeat.OFF, 99).duration + 1000)
        assertEquals(3_661_000L, AlarmFields(1, 1, AlarmRepeat.OFF, 1).duration)
    }
}
