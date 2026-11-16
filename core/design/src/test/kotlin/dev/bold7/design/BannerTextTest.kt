package dev.bold7.design

import java.time.LocalDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class BannerTextTest {
    @Test fun theTimeIsTheClocksHoursAndMinutesAndTheDateTheDaysShortNames() {
        val t = LocalDateTime.of(2026, 11, 13, 0, 44)
        assertEquals("00:44" to "", bannerTime(t, is24Hour = true))
        assertEquals("12:44" to "AM", bannerTime(t, is24Hour = false))
        assertEquals("1:05" to "PM", bannerTime(t.withHour(13).withMinute(5), is24Hour = false))
        assertEquals("Fri 13 Nov", bannerDate(t, Locale.UK))
        // French writes its abbreviations with a dot, which the banner leaves out.
        assertEquals("ven 13 nov", bannerDate(t, Locale.FRANCE))
    }
}
