package dev.bold7.sysui

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StatusIconsTest {
    // Android's slots on the Q25: config_statusBarIcons of its framework-res.apk (sha256 a0bf9cca…,
    // aapt2 dump), the order StatusBarIconList follows, as dumpsys shows it for SystemUI.
    private val q25Slots = listOf(
        "no_calling", "call_strength", "alarm_clock", "rotate", "headset", "data_saver", "ime", "sync_failing",
        "sync_active", "nfc", "tty", "speakerphone", "cdma_eri", "data_connection", "phone_evdo_signal", "phone_signal",
        "secure", "managed_profile", "cast", "screen_record", "vpn", "bluetooth", "camera", "microphone",
        "location", "mute", "volume", "zen", "ethernet", "wifi", "hotspot", "mobile",
        "airplane", "battery", "sensors_off",
    )

    // Sources of each pair in docs/BBOS7-VISUEL.md §18, "Status indicators".
    @Test fun androidSlotsTakeTheThemesImages() = assertEquals(
        listOf(
            "banner_alarm", "banner_profile_vibrate", "banner_profile_silent", "banner_profile_phone_only",
            "banner_bluetooth_connected", "banner_gps_active", "banner_nfc",
        ),
        listOf("alarm_clock", "volume", "mute", "zen", "bluetooth", "location", "nfc").map { statusIconImage(it) },
    )

    // The 9900 tells "on" from "in use" where Android shows only the use: Bluetooth (the guide,
    // p. 262: turned on, connected to a device), GPS (net_rim_Ribbon_GPS: on, then with its waves).
    // Off shows nothing.
    @Test fun onAndInUseHaveTheirOwnImages() {
        assertEquals(
            listOf(null, "banner_bluetooth_on", "banner_bluetooth_connected", "banner_bluetooth_connected"),
            listOf(false to false, true to false, true to true, false to true).map { (on, inUse) -> slotState("bluetooth", on, inUse) },
        )
        assertEquals("banner_gps_on", slotState("location", on = true, inUse = false))
        assertEquals("banner_gps_active", slotState("location", on = true, inUse = true))
        assertNull(slotState("location", on = false, inUse = false))
    }

    // Only those two have two images: the 9900 has no hotspot indicator.
    @Test fun onlyTwoSlotsHaveTwoStates() {
        assertEquals(setOf("bluetooth", "location"), SLOT_STATES.keys)
        assertNull(slotState("hotspot", on = true, inUse = true))
        assertNull(slotState("alarm_clock", on = true, inUse = false))
    }

    // The image of a slot follows its last state, else its single image.
    @Test fun stateOverridesTheSingleImage() {
        assertEquals("banner_bluetooth_on", statusIconImage("bluetooth", mapOf("bluetooth" to "banner_bluetooth_on")))
        assertEquals("banner_bluetooth_connected", statusIconImage("bluetooth", mapOf("bluetooth" to null)))
        assertEquals("banner_alarm", statusIconImage("alarm_clock", mapOf("bluetooth" to "banner_bluetooth_on")))
    }

    // Android's slots the theme has no image for are drawn by us (D, status_*): the eighteen
    // slots of the lot, each with its own image.
    @Test fun slotsWithoutAThemeImageHaveOurOwn() {
        val drawn = listOf(
            "hotspot", "vpn", "tty", "data_saver", "cast", "screen_record", "camera", "microphone", "sensors_off",
            "managed_profile", "sync_active", "sync_failing", "rotate", "headset", "speakerphone", "call_strength",
            "no_calling", "ethernet",
        )
        assertEquals(drawn.map { "status_$it" }, drawn.map { statusIconImage(it) })
        assertEquals(drawn.size, drawn.map { statusIconImage(it) }.toSet().size)
    }

    // Slots the 9900 has nothing for: no image, hidden instead (IndicatorsTest).
    @Test fun otherSlotsKeepAndroidsIcon() {
        for (slot in listOf("ime", "secure", "cdma_eri", "data_connection", "phone_evdo_signal", "embms", null)) {
            assertNull(statusIconImage(slot), slot)
        }
    }

    // Notification icons go through StatusBarIconView too, their slot being the notification's key.
    @Test fun notificationKeysAreNoSlot() = assertNull(statusIconImage("0|com.android.alarm|1|null|10123"))

    // A name without its drawable would throw in SystemUI's process (counted, Android's icon kept).
    @Test fun everyStatusIconImageIsThere() = assertEquals(
        emptyList(),
        (STATUS_ICON_IMAGES.values + SLOT_STATES.values.flatMap { it.toList() })
            .filterNot { File("../../assets-gen/sysui/res/drawable-nodpi/$it.png").exists() },
    )

    /** config_statusBarIcons of the framework overlay, which sets the order of Android's icons. */
    private fun overlaySlots(): List<String> {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("../overlays/android/src/main/res/values/statusbar.xml"))
        val arrays = xml.getElementsByTagName("string-array")
        val array = (0 until arrays.length).map { arrays.item(it) }.single { it.attributes.getNamedItem("name").nodeValue == "config_statusBarIcons" }
        return (0 until array.childNodes.length).map { array.childNodes.item(it) }.filter { it.nodeName == "item" }.map { it.textContent.trim() }
    }

    // Every slot of the Q25 kept, none added: a slot missing from the list would still show,
    // inserted first by StatusBarIconList, but out of place.
    @Test fun overlayKeepsEveryQ25Slot() = assertEquals(q25Slots.sorted(), overlaySlots().sorted())

    // The 9900's indicators first, in the order of STATUS_ICON_IMAGES; Android's others after, in
    // Android's own order.
    @Test fun overlayPutsTheThemesIndicatorsFirst() = assertEquals(
        STATUS_ICON_IMAGES.keys.toList() + q25Slots.filterNot { it in STATUS_ICON_IMAGES },
        overlaySlots(),
    )

    // The classes StatusIcons reaches by name, tied to the Q25's SystemUI like GuardTest does;
    // StatusIconsTargets.kt compiles the members.
    @Test fun statusIconTargetClassesAreInTheQ25SystemUi() = ZipFile("../../stubs/systemui.jar").use { jar ->
        for (cls in listOf(ICON_VIEW, STATUS_ICON_CONTAINER, STATUS_BAR_POLICY, BLUETOOTH_CONTROLLER, LOCATION_CONTROLLER, ICON_CONTROLLER)) {
            assertNotNull(jar.getEntry(cls.replace('.', '/') + ".class"), cls)
        }
    }
}
