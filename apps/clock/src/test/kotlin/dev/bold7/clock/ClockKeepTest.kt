package dev.bold7.clock

import kotlin.test.Test
import dev.bold7.showcase.ShowcaseEvent
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClockKeepTest {
    private val showcase = clockFor(showcase = true) { error("real source opened") }

    @Test
    fun theShowcaseAlarmIsSetOnWeekdays() {
        assertEquals(Alarm(6, 45, AlarmRepeat.WEEKDAYS), showcase.settings.alarm)
        assertEquals(ClockSettings().options, showcase.settings.options)
        assertEquals(null, showcase.snoozeUntil)
        assertEquals(Stopwatch(), showcase.stopwatch)
        assertEquals(false, showcase.timer.running)
    }

    @Test
    fun writesDoNotEscape() {
        val before = showcase.settings
        showcase.settings = before.copy(alarm = Alarm(5, 0, AlarmRepeat.OFF))
        showcase.snoozeUntil = 1L
        showcase.stopwatch = Stopwatch(base = 1000)
        showcase.timer = Countdown(60_000, endsAt = 5)
        assertEquals(before, showcase.settings)
        assertEquals(null, showcase.snoozeUntil)
        assertEquals(Stopwatch(), showcase.stopwatch)
        assertEquals(false, showcase.timer.running)
    }

    @Test
    fun withoutTheShowcaseTheRealKeepIsUsed() {
        assertEquals(showcase, clockFor(showcase = false) { showcase })
    }

    @Test
    fun theVideosAlarmRingsOnScreenOnlyAndNeverOverARealRing() {
        val ring = assertNotNull(showcaseRing(ShowcaseEvent.ALARM, 1000L))
        assertEquals(Ringing(timer = false, since = 1000L, snooze = ClockSettings().options.snooze, alarm = "06:45", showcase = true), ring)
        assertNull(showcaseRing(ShowcaseEvent.MESSAGE, 1000L))
        assertNull(showcaseRing(null, 1000L))
        val real = Ringing(timer = false, since = 5L, snooze = 9, alarm = "07:00")
        assertEquals(real, nextRinging(real, ring))
        assertEquals(real, nextRinging(real, null))
        assertEquals(ring, nextRinging(null, ring))
        assertNull(nextRinging(ring, null))
    }
}
