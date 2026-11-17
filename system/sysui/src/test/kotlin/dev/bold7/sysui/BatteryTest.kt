package dev.bold7.sysui

import android.app.NotificationManager
import android.media.AudioManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryTest {
    // The charger's chip only: the privacy and connected display events keep theirs.
    @Test fun onlyTheBatteryChipIsLeftOut() {
        assertTrue(chipLeftOut(BATTERY_EVENT))
        assertFalse(chipLeftOut("com.android.systemui.statusbar.events.PrivacyEvent"))
        assertFalse(chipLeftOut("com.android.systemui.statusbar.events.ConnectedDisplayEvent"))
    }

    // The tone where Android's notification would have sounded: ringer on, Do Not Disturb off.
    @Test fun lowBatteryToneFollowsRingerAndDoNotDisturb() {
        val all = NotificationManager.INTERRUPTION_FILTER_ALL
        assertEquals(
            listOf(true, false, false, false, true),
            listOf(
                lowBatteryTone(AudioManager.RINGER_MODE_NORMAL, all),
                lowBatteryTone(AudioManager.RINGER_MODE_VIBRATE, all),
                lowBatteryTone(AudioManager.RINGER_MODE_SILENT, all),
                lowBatteryTone(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_PRIORITY),
                lowBatteryTone(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN),
            ),
        )
    }
}
