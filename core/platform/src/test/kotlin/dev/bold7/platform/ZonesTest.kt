package dev.bold7.platform

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZonesTest {
    private val winter = Instant.parse("2026-01-12T12:00:00Z")

    @Test fun aZoneIsNamedByItsCityAndItsOffset() {
        // sim-170: "Casablanca (GMT)" on the simulator; Morocco is +1 this winter.
        assertEquals("Kolkata (GMT+5:30)", zoneLabel("Asia/Kolkata", winter))
        assertEquals("Buenos Aires (GMT-3)", zoneLabel("America/Argentina/Buenos_Aires", winter))
        assertEquals("London (GMT)", zoneLabel("Europe/London", winter))
        assertEquals("Paris (GMT+1)", zoneLabel("Europe/Paris", winter))
        assertEquals("Paris (GMT+2)", zoneLabel("Europe/Paris", Instant.parse("2026-07-16T12:00:00Z")))
    }

    @Test fun theListIsCitiesByOffsetThenNameWithoutTwinLabels() {
        val ids = setOf("Europe/Paris", "Asia/Kolkata", "Europe/London", "UTC", "Etc/GMT+3", "America/Los_Angeles", "America/Indiana/Indianapolis", "America/Indianapolis")
        // Two ids with one label ("Indianapolis (GMT-5)") are one entry.
        val zones = cityZones(winter, ids)
        assertEquals(listOf("America/Los_Angeles", "Indianapolis", "Europe/London", "Europe/Paris", "Asia/Kolkata"), zones.map { if ("Indianapolis" in it) "Indianapolis" else it })
        assertTrue(cityZones(winter).size > 100)
        assertEquals(cityZones(winter).map { zoneLabel(it, winter) }.distinct().size, cityZones(winter).size)
    }

    @Test fun aFieldsZoneThatIsNoCityComesFirst() {
        val ids = setOf("Europe/Paris", "UTC")
        assertEquals(listOf("Europe/Paris"), zoneChoices("Europe/Paris", winter, ids))
        assertEquals(listOf("UTC", "Europe/Paris"), zoneChoices("UTC", winter, ids))
    }
}
