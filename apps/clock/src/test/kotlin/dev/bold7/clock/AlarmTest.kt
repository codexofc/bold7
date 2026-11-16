package dev.bold7.clock

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlarmTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(month: Int, day: Int, hour: Int, minute: Int, second: Int = 0) = ZonedDateTime.of(2026, month, day, hour, minute, second, 0, paris)
    private fun ms(month: Int, day: Int, hour: Int, minute: Int) = at(month, day, hour, minute).toInstant().toEpochMilli()

    // Friday 13 November 2026.
    private val friday = at(11, 13, 6, 30)

    @Test fun anAlarmOffNeverRings() = assertNull(nextRing(Alarm(7, 0, AlarmRepeat.OFF), null, friday))

    @Test fun onRingsTodayIfTheTimeIsStillAheadElseTomorrow() {
        assertEquals(ms(11, 13, 7, 0), nextRing(Alarm(7, 0, AlarmRepeat.ON), null, friday))
        assertEquals(ms(11, 14, 6, 0), nextRing(Alarm(6, 0, AlarmRepeat.ON), null, friday))
        // The very minute it rings, the next one is tomorrow's: never the same ring twice.
        assertEquals(ms(11, 14, 7, 0), nextRing(Alarm(7, 0, AlarmRepeat.ON), null, at(11, 13, 7, 0)))
    }

    @Test fun weekdaysSkipSaturdayAndSunday() {
        // Friday after 7:00: Monday 16/11.
        assertEquals(ms(11, 16, 7, 0), nextRing(Alarm(7, 0, AlarmRepeat.WEEKDAYS), null, at(11, 13, 8, 0)))
        assertEquals(ms(11, 13, 7, 0), nextRing(Alarm(7, 0, AlarmRepeat.WEEKDAYS), null, friday))
        assertEquals(ms(11, 16, 7, 0), nextRing(Alarm(7, 0, AlarmRepeat.WEEKDAYS), null, at(11, 14, 6, 0)))
    }

    @Test fun aSnoozeComesFirstWhenItIsSooner() {
        val snooze = ms(11, 13, 7, 9)
        assertEquals(snooze, nextRing(Alarm(7, 0, AlarmRepeat.ON), snooze, at(11, 13, 7, 0)))
        // Snoozed with the alarm then turned Off: the snooze still rings.
        assertEquals(snooze, nextRing(Alarm(7, 0, AlarmRepeat.OFF), snooze, at(11, 13, 7, 0)))
        // A snooze already past is forgotten.
        assertEquals(ms(11, 14, 7, 0), nextRing(Alarm(7, 0, AlarmRepeat.ON), snooze, at(11, 13, 7, 10)))
    }

    @Test fun summerTimeStillRingsAtTheWallClockTime() {
        // 29/03/2026, Paris goes from 2:00 to 3:00: an alarm at 2:30 rings at 3:30 that day (Java's
        // gap rule), at 7:00 local time the days around.
        val gap = nextRing(Alarm(2, 30, AlarmRepeat.ON), null, ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, paris))
        assertEquals(ZonedDateTime.of(2026, 3, 29, 3, 30, 0, 0, paris).toInstant().toEpochMilli(), gap)
        val before = ZonedDateTime.of(2026, 3, 28, 8, 0, 0, 0, paris)
        assertEquals(ZonedDateTime.of(2026, 3, 29, 7, 0, 0, 0, paris).toInstant().toEpochMilli(), nextRing(Alarm(7, 0, AlarmRepeat.ON), null, before))
    }

    @Test fun aRingMissedByARebootRingsAtOnceForAQuarterOfAnHour() {
        val armed = ms(11, 13, 7, 0)
        assertTrue(missed(armed, armed))
        assertTrue(missed(armed, armed + 14 * 60_000))
        assertFalse(missed(armed, armed + 15 * 60_000))
        assertFalse(missed(armed, armed - 1))
        assertFalse(missed(null, armed))
    }

    @Test fun snoozingSetsTheNextRingSnoozeMinutesFromNow() {
        val now = ms(11, 13, 7, 0)
        assertEquals(now + 9 * 60_000, snoozeUntil(now, 9))
    }

    @Test fun theVolumeOfTheAlarmStreamInTenths() {
        // sim-174, sim-177: Silent, 1 to 10, Escalating; the Q25's alarm stream has 15 steps.
        assertEquals(12, streamLevel(8, 15))
        assertEquals(15, streamLevel(10, 15))
        assertEquals(2, streamLevel(1, 15))
        assertEquals(15, streamLevel(ESCALATING, 15))
        assertEquals(0, streamLevel(SILENT, 15))
    }

    @Test fun escalatingRisesFromATenthToFullInAMinuteTheOthersStayFull() {
        assertEquals(1f, gain(8, 0))
        assertEquals(1f, gain(1, 30_000))
        assertEquals(0.1f, gain(ESCALATING, 0))
        assertEquals(0.55f, gain(ESCALATING, 30_000), 0.001f)
        assertEquals(1f, gain(ESCALATING, 60_000))
        assertEquals(1f, gain(ESCALATING, 600_000))
    }

    @Test fun theDefaultsAreThe9900s() {
        // sim-156, sim-171: 8:00 Off, Evolving Dream, 8, 5 Min., vibration Off.
        assertEquals(ClockSettings(Alarm(8, 0, AlarmRepeat.OFF), AlarmOptions("Dream.m4a", 8, 5, vibrate = false)), ClockSettings())
    }

    @Test fun settingsReadBackWhatWasWrittenAndDefaultOnAnythingElse() {
        val s = ClockSettings(Alarm(6, 45, AlarmRepeat.WEEKDAYS), AlarmOptions("Seaside.m4a", ESCALATING, 15, vibrate = true))
        assertEquals(s, ClockSettings.read(s.write()::get))
        assertEquals(ClockSettings(), ClockSettings.read { null })
        // A broken value falls back to its default only.
        val broken = s.write() + ("repeat" to "SOMETIMES") + ("hour" to "25") + ("snooze" to "7") + ("tone" to "../x")
        val d = ClockSettings()
        assertEquals(
            ClockSettings(s.alarm.copy(hour = d.alarm.hour, repeat = d.alarm.repeat), s.options.copy(snooze = d.options.snooze, tone = d.options.tone)),
            ClockSettings.read(broken::get),
        )
    }

    @Test fun theTimerAndDisplayOptionsAreKeptToo() {
        val s = ClockSettings(
            Alarm(6, 45, AlarmRepeat.WEEKDAYS), AlarmOptions("Seaside.m4a", ESCALATING, 15, vibrate = true),
            TimerOptions("Dream.m4a", SILENT, vibrate = true), DisplayOptions(ClockFace.DIGITAL, "Africa/Casablanca", dim = false),
        )
        assertEquals(s, ClockSettings.read(s.write()::get))
        // An old store without these keys, or with broken ones, reads the defaults.
        assertEquals(ClockSettings().timer, ClockSettings.read(s.write().filterKeys { !it.startsWith("timer") }::get).timer)
        assertEquals(ClockSettings().display, ClockSettings.read((s.write() + ("face" to "ROUND") + ("home_zone" to "Nowhere/Land") + ("dim" to "maybe"))::get).display)
    }

    @Test fun theTimerRingsAsBeforeUnlessOptionsSaysOtherwise() {
        // The timer keeps its own defaults (3 Beeps at 8, without vibration or snooze); the alarm reads its own options.
        assertEquals(RingSpec("3Beeps.m4a", 8, false, 0), ringSpec(true, ClockSettings()))
        assertEquals(RingSpec("Dream.m4a", 8, false, 5), ringSpec(false, ClockSettings()))
        val s = ClockSettings(options = AlarmOptions("Seaside.m4a", 3, 10, true), timer = TimerOptions("Antelope.m4a", ESCALATING, true))
        assertEquals(RingSpec("Antelope.m4a", ESCALATING, true, 0), ringSpec(true, s))
        assertEquals(RingSpec("Seaside.m4a", 3, true, 10), ringSpec(false, s))
    }
}
