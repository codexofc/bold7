package dev.bold7.sysui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The 9900's banner indicators (docs/BBOS7-VISUEL.md §18): strips of the theme sliced by
// tools/import-assets.sh bandeau, one banner_* drawable per state.
class IndicatorsTest {
    private val edge = RadioState(radio = Radio.IN_SERVICE, signalLevel = 4, networkType = 2, dataConnected = true)

    @Test fun wifiLevelFollowsAndroidRssiThresholds() {
        assertEquals(listOf(0, 0, 1, 1, 2, 3, 4, 4), listOf(Int.MIN_VALUE, -89, -88, -78, -77, -66, -55, -30).map(::wifiLevel))
    }

    // net_rim_Ribbon_WlanSignalLevel-icons30x24.png: frame 0 is empty, 1 to 5 light one more arc
    // each. A connected network shows at least the first.
    @Test fun wifiTakesFramesOneToFive() {
        assertEquals(listOf("banner_wifi_1", "banner_wifi_2", "banner_wifi_4", "banner_wifi_5"), listOf(-95, -80, -60, -40).map(::wifiImage))
        assertNull(wifiImage(null))
    }

    // The column itself is Strips' (core/design StripsTest): here, its image.
    @Test fun androidsFiveSignalLevelsOnTheBarsImages() = assertEquals(
        listOf("banner_signal_0", "banner_signal_2", "banner_signal_3", "banner_signal_4", "banner_signal_5"),
        (0..4).map { signalImage(edge.copy(signalLevel = it)) },
    )

    // net_rim_Ribbon_SignalLevel-icons49x19.png: OFF, X, 0 to 5 bars, SOS; the guide (p. 262):
    // OFF the connection turned off, X no coverage, SOS emergency calls only.
    @Test fun signalImageByRadioState() {
        assertEquals("banner_signal_5", signalImage(edge))
        assertEquals("banner_signal_0", signalImage(edge.copy(signalLevel = 0)))
        assertEquals("banner_signal_off", signalImage(edge.copy(radio = Radio.AIRPLANE)))
        assertEquals("banner_signal_x", signalImage(edge.copy(radio = Radio.NO_SERVICE)))
        assertEquals("banner_signal_sos", signalImage(edge.copy(radio = Radio.EMERGENCY_ONLY)))
    }

    // The guide's names (3G, 4G, H+, EDGE, GPRS), in capitals with data, in lowercase without
    // ("edge" of the simulator, which has no data; "EDGE" and "3G" of gsm 019 and 022).
    @Test fun coverageNamesTheNetworkTypeCapitalisedWithData() {
        val names = listOf(1, 2, 3, 8, 9, 10, 15, 17, 13, 20).map { coverageText(edge.copy(networkType = it)) }
        assertEquals(listOf("GPRS", "EDGE", "3G", "3G", "3G", "3G", "H+", "3G", "4G", "4G"), names)
        assertEquals("edge", coverageText(edge.copy(dataConnected = false)))
        assertEquals("h+", coverageText(edge.copy(networkType = 15, dataConnected = false)))
    }

    @Test fun noCoverageTextOutOfServiceNorForAnUnknownType() {
        assertNull(coverageText(edge.copy(radio = Radio.NO_SERVICE)))
        assertNull(coverageText(edge.copy(radio = Radio.AIRPLANE)))
        assertNull(coverageText(edge.copy(networkType = 0)))
        assertNull(coverageText(edge.copy(networkType = 16))) // GSM alone: no data at all
    }

    @Test fun roamingOnlyInService() {
        assertEquals("banner_roaming", roamingImage(edge.copy(roaming = true)))
        assertNull(roamingImage(edge))
        assertNull(roamingImage(edge.copy(roaming = true, radio = Radio.NO_SERVICE)))
    }

    // The column is Strips' (core/design StripsTest), "?" for an unknown level included.
    @Test fun batteryImageIsItsColumn() =
        assertEquals(listOf("banner_battery_0", "banner_battery_6", "banner_battery_10", "banner_battery_11"), listOf(0, 55, 100, -1).map { batteryImage(it, plugged = false) })

    @Test fun batteryOnPowerTakesThePluggedRow() =
        assertEquals("banner_battery_charging_5", batteryImage(42, plugged = true))

    // ACTION_BATTERY_CHANGED's extras, which the bar reads under BenOS too (BatteryMeterView only
    // tells the level and "plugged in"): level on its scale; the plug when full on power (5, or 2
    // at 100 %), the battery's own row under the bolt while charging (2), as the home's banner.
    @Test fun batteryBroadcastReadAsTheBanner() = assertEquals(
        listOf("banner_battery_charging_10", "banner_battery_charging_10", "banner_battery_5", "banner_battery_5", "banner_battery_5", "banner_battery_11"),
        listOf(batteryImage(99, 100, 5), batteryImage(50, 50, 2), batteryImage(21, 50, 2), batteryImage(42, 100, 3), batteryImage(42, 100, 4), batteryImage(-1, 100, 3)),
    )

    @Test fun theBoltWhileChargingUnderAHundred() {
        assertEquals(listOf(true, false, false, false), listOf(charging(21, 50, 2), charging(50, 50, 2), charging(99, 100, 5), charging(42, 100, 3)))
        assertEquals(listOf(null, "banner_bolt_1", "banner_bolt_5"), listOf(0, 1, 5).map(::boltImage))
    }

    @Test fun radioSlotsBlockedOnlyInDressedBars() {
        for (slot in listOf("wifi", "mobile", "airplane")) {
            assertEquals(false, androidIconShown(slot, visible = true, inDressedBar = true), slot)
            assertEquals(true, androidIconShown(slot, visible = true, inDressedBar = false), slot)
        }
        assertEquals(true, androidIconShown("bluetooth", visible = true, inDressedBar = true))
        assertEquals(false, androidIconShown("bluetooth", visible = false, inDressedBar = true))
        assertEquals(false, androidIconShown(null, visible = false, inDressedBar = false))
    }

    // What the 9900's banner has nothing for (docs/BBOS7-VISUEL.md §18, "Slots
    // with no equivalent") hides in the dressed bars, and stays Android's in the shade's header.
    @Test fun slotsWithoutAnEquivalentHiddenInDressedBars() {
        for (slot in listOf("ime", "secure", "cdma_eri", "data_connection", "phone_evdo_signal", "phone_signal", "embms")) {
            assertEquals(false, androidIconShown(slot, visible = true, inDressedBar = true), slot)
            assertEquals(true, androidIconShown(slot, visible = true, inDressedBar = false), slot)
        }
    }

    @Test fun carrierNameOnlyInServiceSimFirst() {
        val named = edge.copy(simOperator = "Free", networkOperator = "F Free")
        assertEquals("Free", operatorName(named))
        assertEquals("F Free", operatorName(named.copy(simOperator = " ")))
        assertEquals(null, operatorName(named.copy(radio = Radio.AIRPLANE)))
        assertEquals(null, operatorName(named.copy(radio = Radio.NO_SERVICE)))
        assertEquals(null, operatorName(edge))
    }

    // Every image the radio and battery functions can name, so that none is missing at run time
    // (Images would throw in SystemUI's process, the indicator would be counted and lost).
    @Test fun everyBannerImageIsThere() {
        val states = listOf(Radio.IN_SERVICE, Radio.NO_SERVICE, Radio.EMERGENCY_ONLY, Radio.AIRPLANE)
            .flatMap { r -> (0..4).map { edge.copy(radio = r, signalLevel = it, roaming = true) } }
        val names = states.map(::signalImage) + states.mapNotNull(::roamingImage) +
            (-100..-30).mapNotNull(::wifiImage) +
            (-1..100).flatMap { listOf(batteryImage(it, false), batteryImage(it, true)) } + (0..5).mapNotNull(::boltImage)
        assertEquals(emptyList(), names.distinct().filterNot { File("../../assets-gen/sysui/res/drawable-nodpi/$it.png").exists() })
    }
}
