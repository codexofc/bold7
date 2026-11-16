package dev.bold7.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class WifiTest {
    @Test
    fun securityFromCapabilities() {
        assertEquals(WifiSecurity.OPEN, wifiSecurity("[ESS]"))
        assertEquals(WifiSecurity.OPEN, wifiSecurity(""))
        assertEquals(WifiSecurity.PERSONAL, wifiSecurity("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]"))
        // WPA3 transition mode: a PSK configuration joins it.
        assertEquals(WifiSecurity.PERSONAL, wifiSecurity("[RSN-PSK+SAE-CCMP][ESS][MFPC]"))
        assertEquals(WifiSecurity.WPA3, wifiSecurity("[RSN-SAE-CCMP][ESS][MFPR][MFPC]"))
        assertEquals(WifiSecurity.ENTERPRISE, wifiSecurity("[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]"))
        assertEquals(WifiSecurity.WEP, wifiSecurity("[WEP][ESS]"))
        assertEquals(WifiSecurity.OWE, wifiSecurity("[RSN-OWE-CCMP][ESS][MFPR][MFPC]"))
    }

    @Test
    fun barsFromLevel() {
        // Android's five levels (0 to 4) on the theme's three.
        assertEquals(listOf(1, 1, 2, 2, 3), (0..4).map { wifiBars(it, 4) })
        assertEquals(1, wifiBars(-3, 4))
        assertEquals(3, wifiBars(9, 4))
        assertEquals(listOf(1, 2, 3), (0..2).map { wifiBars(it, 2) })
    }
}
