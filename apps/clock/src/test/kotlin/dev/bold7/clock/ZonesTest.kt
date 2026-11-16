package dev.bold7.clock

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZonesTest {
    private val winter = Instant.parse("2026-11-16T12:00:00Z")

    @Test fun theHomeLineTellsTheTimeThereOnlyForAnotherZone() {
        val paris = ZoneId.of("Europe/Paris")
        assertEquals("New York 07:00", homeLine("America/New_York", paris, winter))
        assertNull(homeLine(null, paris, winter))
        assertNull(homeLine("Europe/Paris", paris, winter))
    }
}
