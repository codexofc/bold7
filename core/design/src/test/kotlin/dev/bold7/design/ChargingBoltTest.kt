package dev.bold7.design

import kotlin.test.Test
import kotlin.test.assertEquals

class ChargingBoltTest {
    // BatteryManager's statuses: 2 charging, 3 discharging, 4 not charging, 5 full.
    @Test fun theBoltWhileChargingUnderAHundred() {
        assertEquals(true, ChargingBolt.shows(status = 2, percent = 50))
        assertEquals(true, ChargingBolt.shows(status = 2, percent = 0))
        assertEquals(true, ChargingBolt.shows(status = 2, percent = 99))
    }

    // The Q25 says Charging at 100 % while its charger says Full (B1): Android's level decides.
    @Test fun noBoltWhenFullUnpluggedOrUnknown() {
        assertEquals(false, ChargingBolt.shows(status = 2, percent = 100))
        assertEquals(false, ChargingBolt.shows(status = 5, percent = 100))
        assertEquals(false, ChargingBolt.shows(status = 5, percent = 97))
        assertEquals(false, ChargingBolt.shows(status = 3, percent = 50))
        assertEquals(false, ChargingBolt.shows(status = 4, percent = 50))
        assertEquals(false, ChargingBolt.shows(status = 2, percent = -1))
    }

    // The plug: on power and not charging (full, sim-415); charging draws the battery's own row
    // under the bolt (guide p. 20, sim-470).
    @Test fun thePlugOnlyWhenFullOnPower() {
        assertEquals(Strips.BATTERY_PLUGGED_ROW, Strips.batteryRow(status = 2, percent = 100))
        assertEquals(Strips.BATTERY_PLUGGED_ROW, Strips.batteryRow(status = 5, percent = 97))
        assertEquals(0, Strips.batteryRow(status = 2, percent = 50))
        assertEquals(0, Strips.batteryRow(status = 3, percent = 100))
        assertEquals(0, Strips.batteryRow(status = 4, percent = 50))
    }

    // homescreen-banner.pme: l00 to l11 = images 0 1 2 3 4 5 4 3 2 1 0 0 at keyTimes 0 .05 .1 .15
    // .2 .3 .75 .8 .85 .9 .95 1 of dur 1800 ms (A).
    @Test fun theImagesFollowThePmeKeyTimes() {
        val at = listOf(0L, 89, 90, 180, 270, 360, 539, 540, 1349, 1350, 1440, 1530, 1620, 1710, 1799, 1800, 1890, 3600 + 600)
        assertEquals(listOf(0, 0, 1, 2, 3, 4, 4, 5, 5, 4, 3, 2, 1, 0, 0, 0, 1, 5), at.map(ChargingBolt::image))
    }

    // The next change, for the one redraw it needs: none in between.
    @Test fun theNextChangeIsTheNextKeyTime() {
        assertEquals(90L, ChargingBolt.untilNext(0))
        assertEquals(1L, ChargingBolt.untilNext(89))
        assertEquals(810L, ChargingBolt.untilNext(540))
        assertEquals(180L, ChargingBolt.untilNext(1710)) // 0 from 1710 to 1890: 00 then the next cycle's l00
        assertEquals(90L, ChargingBolt.untilNext(1800))
    }
}
