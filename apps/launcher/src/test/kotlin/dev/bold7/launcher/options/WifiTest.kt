package dev.bold7.launcher.options

import dev.bold7.platform.WifiSaved
import dev.bold7.platform.WifiSecurity
import dev.bold7.platform.WifiSecurity.ENTERPRISE
import dev.bold7.platform.WifiSecurity.OPEN
import dev.bold7.platform.WifiSecurity.OWE
import dev.bold7.platform.WifiSecurity.PERSONAL
import dev.bold7.platform.WifiSecurity.WEP
import dev.bold7.platform.WifiSecurity.WPA3
import dev.bold7.platform.WifiSeen
import dev.bold7.platform.WifiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WifiTest {
    private val state = WifiState(
        on = true,
        allowed = true,
        seen = listOf(
            WifiSeen("Cafe", 1, OPEN),
            WifiSeen("Home", 2, PERSONAL),
            WifiSeen("Home", 3, PERSONAL),
            WifiSeen("Office", 3, ENTERPRISE),
            WifiSeen("attic", 3, WPA3),
            WifiSeen("Old", 2, WEP),
            WifiSeen("Station", 2, OWE),
        ),
        saved = listOf(WifiSaved(4, "Home", PERSONAL), WifiSaved(9, "Gone", OPEN)),
        connected = "Station",
    )

    private fun names(s: WifiScreen) = s.networks.map { it.ssid }

    @Test
    fun oneRowPerNameConnectedFirstThenSignalThenName() {
        val s = WifiScreen(state)
        assertEquals(listOf("Station", "attic", "Home", "Office", "Old", "Cafe"), names(s))
        val home = s.networks.first { it.ssid == "Home" }
        assertEquals(3, home.bars)
        assertEquals(4, home.saved?.id)
        assertEquals(true, s.networks.first().connected)
    }

    @Test
    fun boxAndSavedComeAfterTheNetworks() {
        val rows = WifiScreen(state).rows
        assertEquals(WifiRow.OpenOnly(false), rows[6])
        assertEquals(WifiRow.Saved, rows[7])
        assertEquals(8, rows.size)
    }

    @Test
    fun openOnlyKeepsOpenAndEnhancedOpen() {
        val (s, action) = WifiScreen(state, focus = 6).click()
        assertEquals(WifiAction.None, action)
        assertEquals(listOf("Station", "Cafe"), names(s))
        // The focus follows the box.
        assertEquals(WifiRow.OpenOnly(true), s.rows[s.focus])
        val (back, _) = s.click()
        assertEquals(6, names(back).size)
        assertEquals(WifiRow.OpenOnly(false), back.rows[back.focus])
    }

    @Test
    fun clickOnEachKindOfRow() {
        val s = WifiScreen(state)
        fun on(name: String) = s.click(s.rows.indexOfFirst { it is WifiRow.Network && it.ssid == name }).second
        assertEquals(WifiAction.None, on("Station"))
        assertEquals(WifiAction.Connect(4, "Home"), on("Home"))
        assertEquals(WifiAction.Join("attic", WPA3, password = true), on("attic"))
        assertEquals(WifiAction.Join("Cafe", OPEN, password = false), on("Cafe"))
        assertEquals(WifiAction.Unsupported("Office", ENTERPRISE), on("Office"))
        assertEquals(WifiAction.Unsupported("Old", WEP), on("Old"))
        assertEquals(WifiAction.OpenSaved, s.click(7).second)
        assertEquals(3, s.click(3).first.focus)
    }

    @Test
    fun focusStopsAtTheEnds() {
        val s = WifiScreen(state)
        assertEquals(0, s.step(down = false).focus)
        var f = s
        repeat(20) { f = f.step(down = true) }
        assertEquals(7, f.focus)
    }

    @Test
    fun focusFollowsItsNetworkAcrossScans() {
        val s = WifiScreen(state, focus = 2) // Home
        val next = s.with(state.copy(seen = state.seen + WifiSeen("Zeta", 3, PERSONAL) + WifiSeen("Alpha", 3, OPEN)))
        assertEquals("Home", (next.rows[next.focus] as WifiRow.Network).ssid)
        // Gone from the scan: the focus keeps its index.
        val gone = s.with(state.copy(seen = state.seen.filter { it.ssid != "Home" }))
        assertEquals(2, gone.focus)
        // The box keeps the focus when the list changes above it.
        val box = WifiScreen(state, focus = 6).with(state.copy(seen = emptyList()))
        assertEquals(WifiRow.OpenOnly(false), box.rows[box.focus])
    }

    @Test
    fun noticeWhenNothingToShow() {
        assertNotNull(WifiScreen(state.copy(on = false)).notice)
        assertNotNull(WifiScreen(state.copy(allowed = false, seen = emptyList())).notice)
        assertEquals("No Wi-Fi networks found.", WifiScreen(state.copy(seen = emptyList())).notice)
        assertNull(WifiScreen(state).notice)
    }

    @Test
    fun savedNetworksByNameFocusFollowsTheirId() {
        val s = SavedWifiScreen(listOf(WifiSaved(4, "home", PERSONAL), WifiSaved(9, "Attic", OPEN)))
        assertEquals(listOf("Attic", "home"), s.rows.map { it.ssid })
        val down = s.step(down = true)
        assertEquals(4, down.focused?.id)
        assertEquals(4, down.with(listOf(WifiSaved(1, "Zed", OPEN)) + s.saved).focused?.id)
        // Forgotten: the focus stays in the list.
        assertEquals(9, down.with(listOf(WifiSaved(9, "Attic", OPEN))).focused?.id)
        assertNull(SavedWifiScreen().focused)
    }

    @Test
    fun passwordRules() {
        assertNotNull(passwordProblem(PERSONAL, "short"))
        assertNull(passwordProblem(PERSONAL, "eight888"))
        assertNotNull(passwordProblem(PERSONAL, "x".repeat(64)))
        assertNull(passwordProblem(PERSONAL, "0123456789abcdef".repeat(4)))
        assertNotNull(passwordProblem(PERSONAL, "accentué1"))
        assertNotNull(passwordProblem(WPA3, ""))
        assertNull(passwordProblem(WPA3, "a"))
        assertNull(passwordProblem(OPEN, ""))
    }

    @Test
    fun locksAndLabels() {
        assertEquals(listOf(false, false, true, true, true, true), WifiSecurity.entries.map(::locked))
        assertEquals("WPA/WPA2 Personal", securityLabel(PERSONAL))
    }
}
