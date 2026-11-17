package dev.bold7.launcher.options

import dev.bold7.platform.DeviceFacts
import kotlin.test.Test
import kotlin.test.assertEquals

internal fun facts(
    percent: Int = 80, status: Int = 3, health: Int = 2, plugged: Int = 0, tenths: Int = 312, mv: Int = 4012,
    total: Long = 128_000_000_000L, free: Long = 32_000_000_000L,
) = DeviceFacts(
    "Q25", "Zinwa", "14", "2026-10-05", "BenOS 14.2", "5.15.0", total, free, percent, status, health, plugged, tenths, mv,
    encrypted = true, timeZoneId = "America/Toronto", timeZoneOffsetMinutes = -300, locale24Hour = false,
)

class DeviceInfoTest {
    @Test
    fun `sizes are in decimal units like the phone's own storage figures`() {
        assertEquals("0 B", byteSize(0))
        assertEquals("999 B", byteSize(999))
        assertEquals("1.5 MB", byteSize(1_500_000))
        assertEquals("32.0 GB", byteSize(32_000_000_000))
    }


    @Test
    fun `About lists the model, software and build the Q25 reports`() {
        val rows = aboutRows(facts())
        assertEquals(listOf("Model", "Manufacturer", "Android Version", "Security Patch", "Software Build", "Kernel"), rows.map { it.first })
        assertEquals("Q25", rows[0].second)
        assertEquals("BenOS 14.2", rows[4].second)
    }

    @Test
    fun `Storage shows the total, the free space and the share used`() {
        val rows = storageRows(facts())
        assertEquals(listOf("Total", "Free", "Used"), rows.map { it.first })
        assertEquals("128.0 GB", rows[0].second)
        assertEquals("32.0 GB", rows[1].second)
        assertEquals("75%", rows[2].second)
        assertEquals("0%", storageRows(facts(total = 0, free = 0))[2].second)
    }

    @Test
    fun `Battery says its level, state, health, source, temperature and voltage`() {
        val rows = batteryRows(facts()).toMap()
        assertEquals("80%", rows["Level"])
        assertEquals("Discharging", rows["Status"])
        assertEquals("Good", rows["Health"])
        assertEquals("Not plugged in", rows["Power Source"])
        assertEquals("31.2 C", rows["Temperature"])
        assertEquals("4.01 V", rows["Voltage"])
        val charging = batteryRows(facts(status = 2, plugged = 2, health = 3)).toMap()
        assertEquals("Charging", charging["Status"])
        assertEquals("USB", charging["Power Source"])
        assertEquals("Overheat", charging["Health"])
        assertEquals("Wireless", batteryRows(facts(plugged = 4)).toMap()["Power Source"])
        // The Q25 stays Charging at 100 % while its charger says Full (B1): the level decides, as the banner's bolt.
        assertEquals("Full", batteryRows(facts(percent = 100, status = 2, plugged = 2)).toMap()["Status"])
        assertEquals("Charging", batteryRows(facts(percent = 99, status = 2, plugged = 2)).toMap()["Status"])
    }

    @Test
    fun `Encryption says whether the phone's storage is encrypted`() {
        assertEquals("On", encryptionLabel(facts()))
        assertEquals("Off", encryptionLabel(facts().copy(encrypted = false)))
    }
}
