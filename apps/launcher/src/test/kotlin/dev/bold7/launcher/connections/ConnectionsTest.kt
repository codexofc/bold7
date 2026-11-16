package dev.bold7.launcher.connections

import dev.bold7.platform.Connection
import dev.bold7.platform.Connection.BLUETOOTH
import dev.bold7.platform.Connection.MOBILE
import dev.bold7.platform.Connection.NFC
import dev.bold7.platform.Connection.WIFI
import dev.bold7.platform.ConnectionReadings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionsTest {
    private val all = setOf(MOBILE, WIFI, BLUETOOTH, NFC)
    private fun state(vararg on: Connection, airplane: Boolean = false, available: Set<Connection> = all) =
        ConnectionsState(available, ConnectionReadings(on.toSet(), network = if (MOBILE in on) "Free" else "", airplane = airplane))

    @Test fun boxesFollowOs7sOrderAndOnlyThePhonesConnections() {
        // gsm 006 (C): Mobile Network, Wi-Fi, Bluetooth, Near Field Communication.
        assertEquals(listOf(MOBILE, WIFI, BLUETOOTH, NFC), state().boxes)
        assertEquals(listOf(MOBILE, WIFI, NFC), state(available = setOf(NFC, WIFI, MOBILE)).boxes)
    }

    @Test fun itemsAreTheBannerTheBoxesTheButtonTheAlarmAndOptionsAndStatus() {
        // sim-23, sim-24, gsm 007 (C); the banner first: the trackpad leaves it downwards (sim-112).
        assertEquals(
            listOf(
                Item.Banner, Item.Box(MOBILE), Item.Box(WIFI), Item.Box(BLUETOOTH), Item.Box(NFC), Item.AllOff, Item.Alarm,
                Item.Entry(Page.NETWORKS), Item.Entry(Page.WIFI_NETWORKS), Item.Entry(Page.SERVICE_STATUS), Item.Entry(Page.OPTIONS),
            ),
            state().items,
        )
    }

    @Test fun aBoxOnTurnsItsConnectionOffAndOneOffTurnsItOn() {
        assertEquals(listOf(Effect.Switch(WIFI, false)), state(WIFI).click(Item.Box(WIFI)).second)
        assertEquals(listOf(Effect.Switch(BLUETOOTH, true)), state(WIFI).click(Item.Box(BLUETOOTH)).second)
        assertEquals(listOf(Effect.Switch(MOBILE, false)), state(MOBILE).click(Item.Box(MOBILE)).second)
    }

    @Test fun aBoxDoesNotChangeTheStateByItselfAndroidsReadingDoes() {
        val s = state(WIFI)
        assertEquals(s, s.click(Item.Box(WIFI)).first)
        val read = s.observed(ConnectionReadings(emptySet()))
        assertFalse(WIFI in read.readings.on)
    }

    @Test fun allOffTurnsOffWhatIsOnAndRemembersIt() {
        // Guide p. 258 (B): All Off; sim-117 (C): the button becomes Restore.
        val (s, effects) = state(MOBILE, WIFI).click(Item.AllOff)
        assertEquals(listOf(Effect.Switch(MOBILE, false), Effect.Switch(WIFI, false)), effects)
        assertEquals(setOf(MOBILE, WIFI), s.restore)
        assertTrue(s.restoring)
    }

    @Test fun allOffWithNothingOnStillOffersRestore() {
        val (s, effects) = state().click(Item.AllOff)
        assertEquals(emptyList(), effects)
        assertTrue(s.restoring)
    }

    @Test fun restoreTurnsBackOnWhatWasOnAndForgetsIt() {
        // Guide p. 258 (B): "turn on the network connections that were turned on previously".
        val off = state(MOBILE, WIFI).click(Item.AllOff).first.observed(ConnectionReadings(emptySet()))
        val (s, effects) = off.click(Item.AllOff)
        assertEquals(listOf(Effect.Switch(MOBILE, true), Effect.Switch(WIFI, true)), effects)
        assertNull(s.restore)
        assertFalse(s.restoring)
    }

    @Test fun aBoxTurnedOnByHandForgetsRestore() {
        // sim-119 (C): Wi-Fi checked after All Off, the button is All Off again.
        val off = state(MOBILE, WIFI).click(Item.AllOff).first.observed(ConnectionReadings(emptySet()))
        val (s, effects) = off.click(Item.Box(WIFI))
        assertEquals(listOf(Effect.Switch(WIFI, true)), effects)
        assertNull(s.restore)
    }

    @Test fun aBoxTurnedOffByHandKeepsRestore() {
        val off = state(MOBILE, WIFI).click(Item.AllOff).first
        assertEquals(setOf(MOBILE, WIFI), off.click(Item.Box(WIFI)).first.restore)
    }

    @Test fun androidsReadingsDoNotForgetRestore() {
        // The radio takes seconds to go off: a reading in between still has it on (D).
        val off = state(MOBILE, WIFI).click(Item.AllOff).first
        assertEquals(setOf(MOBILE, WIFI), off.observed(ConnectionReadings(setOf(MOBILE))).restore)
    }

    @Test fun inAndroidsAirplaneModeTheMobileNetworkOpensAndroidsPage() {
        // Android's airplane mode needs a signature permission: its page turns it off.
        assertEquals(listOf(Effect.Open(Page.AIRPLANE)), state(airplane = true).click(Item.Box(MOBILE)).second)
        assertEquals(listOf(Effect.Switch(MOBILE, false)), state(MOBILE, airplane = true).click(Item.Box(MOBILE)).second)
        val off = state(MOBILE, WIFI).click(Item.AllOff).first.observed(ConnectionReadings(emptySet(), airplane = true))
        assertEquals(listOf(Effect.Open(Page.AIRPLANE), Effect.Switch(WIFI, true)), off.click(Item.AllOff).second)
    }

    @Test fun restoreSkipsAConnectionThePhoneNoLongerHas() {
        val s = ConnectionsState(setOf(WIFI), ConnectionReadings(), restore = setOf(WIFI, NFC))
        assertEquals(listOf(Effect.Switch(WIFI, true)), s.click(Item.AllOff).second)
    }

    @Test fun theAlarmAndOptionsAndStatusOpenTheirPages() {
        assertEquals(listOf(Effect.Open(Page.ALARMS)), state().click(Item.Alarm).second)
        assertEquals(listOf(Effect.Open(Page.SERVICE_STATUS)), state().click(Item.Entry(Page.SERVICE_STATUS)).second)
        assertEquals(emptyList(), state().click(Item.Banner).second)
    }

    @Test fun onlyTheBoxesAndTheButtonKeepThePanelOpen() {
        // sim-117 (C): All Off leaves the panel open; a box too (sim-119).
        assertFalse(Item.Box(WIFI).closes)
        assertFalse(Item.AllOff.closes)
        assertTrue(Item.Alarm.closes)
        assertTrue(Item.Entry(Page.OPTIONS).closes)
        assertTrue(Item.Banner.closes)
    }

    @Test fun statusIsTheNetworksNameForTheMobileNetworkOnForTheOthers() {
        // sim-23 (C): "Simulated Network" and "On" in green; nothing when off.
        val s = state(MOBILE, WIFI)
        assertEquals("Free", s.status(MOBILE, "On"))
        assertEquals("On", s.status(WIFI, "On"))
        assertNull(s.status(BLUETOOTH, "On"))
        assertNull(state(MOBILE).observed(ConnectionReadings(setOf(MOBILE), network = "")).status(MOBILE, "On"))
    }

    @Test fun aRefusedSwitchOpensItsAndroidPage() {
        assertEquals(Page.WIFI, refusedPage(WIFI))
        assertEquals(Page.MOBILE, refusedPage(MOBILE))
        assertEquals(Page.BLUETOOTH, refusedPage(BLUETOOTH))
        assertEquals(Page.NFC, refusedPage(NFC))
    }
}

// Audit 2 (sim-23): Mobile Network leads the boxes, whatever else the phone has.
class MobileBoxTest {
    @Test fun mobileNetworkLeadsWiFiBluetoothAndNfc() {
        val boxes = ConnectionsState(setOf(Connection.NFC, Connection.BLUETOOTH, Connection.WIFI, Connection.MOBILE)).boxes
        assertEquals(listOf(Connection.MOBILE, Connection.WIFI, Connection.BLUETOOTH, Connection.NFC), boxes)
    }
}
