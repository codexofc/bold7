package dev.bold7.sysui

import kotlin.test.Test
import kotlin.test.assertEquals

class ShowcaseCountsTest {
    @Test fun theBannersCountTheShowcaseWithoutAskingTheHub() {
        val counters = kindCounters(unreadRows(true) { error("real Hub read") })
        assertEquals(mapOf("banner_count_sms" to 3, "banner_count_email" to 2, "banner_count_missed_call" to 1), counters.associate { it.image to it.count })
        assertEquals(3, counters.size)
    }

    @Test fun theHubsCountsServeWhenItIsOff() {
        assertEquals(mapOf("SMS" to 7), unreadRows(false) { mapOf("SMS" to 7) })
    }

    @Test fun theStorysTextAddsOneToTheTextsCounter() {
        val counters = kindCounters(unreadRows(true, dev.bold7.showcase.ShowcaseEvent.MESSAGE) { error("real Hub read") })
        assertEquals(4, counters.single { it.image == "banner_count_sms" }.count)
    }
}
