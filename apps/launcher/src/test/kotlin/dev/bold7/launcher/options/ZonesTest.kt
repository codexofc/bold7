package dev.bold7.launcher.options

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZonesTest {
    // A winter instant: Paris on GMT+1.
    private val winter = Instant.parse("2026-01-15T12:00:00Z")

    @Test
    fun namesAsOs7() {
        assertEquals("Casablanca (GMT+1)", zoneName("Africa/Casablanca", winter))
        assertEquals("Paris (GMT+1)", zoneName("Europe/Paris", winter))
        assertEquals("Kolkata (GMT+5:30)", zoneName("Asia/Kolkata", winter))
        assertEquals("Buenos Aires (GMT-3)", zoneName("America/Argentina/Buenos_Aires", winter))
        assertEquals("London (GMT)", zoneName("Europe/London", winter))
    }

    @Test
    fun zonesByOffsetThenName() {
        val ids = setOf("Europe/Paris", "Europe/London", "America/Toronto", "Etc/UTC", "Europe/Berlin", "UTC")
        assertEquals(listOf("America/Toronto", "Europe/London", "Europe/Berlin", "Europe/Paris"), timeZones(winter, "Europe/Paris", ids))
        // A phone on a zone off the city list keeps it, at its offset.
        assertEquals(listOf("America/Toronto", "Europe/London", "Etc/UTC", "Europe/Berlin", "Europe/Paris"), timeZones(winter, "Etc/UTC", ids))
        assertTrue("Europe/Paris" in timeZones(winter, "Europe/Paris"))
    }

    @Test
    fun languagesInThemselvesByName() {
        assertEquals("Français (France)", languageName("fr-FR"))
        assertEquals("English (United States)", languageName("en-US"))
        assertEquals(listOf("de-DE", "en-US", "fr-FR"), displayLanguages(listOf("fr-FR", "en-US"), "de-DE"))
        assertEquals(listOf("en-US", "fr-FR"), displayLanguages(listOf("fr-FR", "en-US", "en-US"), "en-US"))
    }
}
