package dev.bold7.calendar

import kotlin.test.Test
import kotlin.test.assertEquals

class RemindersTest {
    private val t = 1_000_000_000_000L
    private fun alert(id: Long, alarm: Long = t, begin: Long = t + 900_000) = Alert(id, begin, begin + 3_600_000, alarm, "e$id")

    @Test fun onlyTheRemindersOfTheBroadcastInstantNotShownYet() {
        val held = listOf(alert(2, begin = t + 1_800_000), alert(1), alert(3, alarm = t - 60_000))
        assertEquals(listOf(alert(1), alert(2, begin = t + 1_800_000)), toShow(held, t, t, emptyMap()))
        assertEquals(listOf(alert(2, begin = t + 1_800_000)), toShow(held, t, t, mapOf(alert(1).key to t)))
    }

    @Test fun aForgedBroadcastCannotBringAReminderEarly() =
        assertEquals(emptyList(), toShow(listOf(alert(1, alarm = t + 60_000)), t + 60_000, t, emptyMap()))

    @Test fun aBootRebroadcastShowsAMissedReminderOnceOnly() {
        // The provider broadcasts again after a boot the reminders of the last 24 h (rescheduleMissedAlarms).
        val missed = alert(1, alarm = t - 3_600_000)
        val first = toShow(listOf(missed), missed.alarmTime, t, emptyMap())
        assertEquals(listOf(missed), first)
        val shown = remember(emptyMap(), first, t)
        assertEquals(emptyList(), toShow(listOf(missed), missed.alarmTime, t + 1000, shown))
    }

    @Test fun shownRemindersAreForgottenAfterTwoDays() {
        val shown = remember(emptyMap(), listOf(alert(1)), t)
        assertEquals(setOf(alert(1).key, alert(2).key), remember(shown, listOf(alert(2)), t + SHOWN_KEEP_MS - 1).keys)
        assertEquals(setOf(alert(2).key), remember(shown, listOf(alert(2)), t + SHOWN_KEEP_MS).keys)
    }

    @Test fun snoozeIsFiveMinutesByDefault() = assertEquals(t + 300_000, snoozedUntil(t))
}
