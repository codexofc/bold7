package dev.bold7.sysui

// The 9900's indicators in place of Android's status icons, pure.
// Frames of net_rim_theme_BlackBerry7_640x480_b, banner_* drawables sliced by
// tools/import-assets.sh bandeau. Sources of each pair in docs/BBOS7-VISUEL.md §18,
// "Status indicators".

/**
 * The 9900's image for each of Android's status icon slots (the framework strings status_bar_*,
 * as SystemUI names them on the Q25), in the banner's order, left to right: the order the
 * framework overlay gives Android (system/overlays/android, statusbar.xml). The alarm first, as
 * AlarmIndicator follows the time in banner.pme, then the profile (D), then Bluetooth, GPS and NFC
 * in the order of msg_banner_dynamic_indicators (theme.xml). Android keeps deciding when each
 * shows. A slot left out keeps Android's icon, except UNSHOWN_SLOTS, hidden in the dressed bars:
 * the 9900 has nothing for them.
 *
 * The slots from hotspot down have no image in the theme: `status_*`, drawn by
 * `tools/import-assets.sh indicateurs` in the style of the net_rim_Ribbon_* strips (D).
 */
val STATUS_ICON_IMAGES = mapOf(
    "alarm_clock" to "banner_alarm", // an alarm set ("Alarm is set", the guide p. 19)
    "volume" to "banner_profile_vibrate", // ringer on vibrate: Vibrate Only
    "mute" to "banner_profile_silent", // ringer silent: Silent
    "zen" to "banner_profile_phone_only", // Do Not Disturb: Phone Calls Only, the nearest (D)
    "bluetooth" to "banner_bluetooth_connected", // a device connected
    "location" to "banner_gps_active", // location in use
    "nfc" to "banner_nfc", // NFC on (MediaTek's PhoneStatusBarPolicy shows it)
    "hotspot" to "status_hotspot",
    "vpn" to "status_vpn",
    "tty" to "status_tty",
    "data_saver" to "status_data_saver",
    "cast" to "status_cast",
    "screen_record" to "status_screen_record",
    "camera" to "status_camera",
    "microphone" to "status_microphone",
    "sensors_off" to "status_sensors_off",
    "managed_profile" to "status_managed_profile",
    "sync_active" to "status_sync_active",
    "sync_failing" to "status_sync_failing",
    "rotate" to "status_rotate",
    "headset" to "status_headset",
    "speakerphone" to "status_speakerphone",
    "call_strength" to "status_call_strength", // Wi-Fi calling registered
    "no_calling" to "status_no_calling", // Wi-Fi calling not registered
    "ethernet" to "status_ethernet",
)

/**
 * The slots the 9900 shows in two states where Android shows one, its use: the image once on,
 * then in use. Bluetooth "turned on" and "connected to a device" (the guide, p. 262; the grey and
 * white frames of net_rim_bluetooth_banner); GPS on, then with its waves (net_rim_Ribbon_GPS).
 */
val SLOT_STATES = mapOf(
    "bluetooth" to ("banner_bluetooth_on" to "banner_bluetooth_connected"),
    "location" to ("banner_gps_on" to "banner_gps_active"),
)

/** The 9900's image for a two-state [slot], null when off (nothing shows) or for any other slot. */
fun slotState(slot: String, on: Boolean, inUse: Boolean): String? {
    val (onImage, inUseImage) = SLOT_STATES[slot] ?: return null
    return when {
        inUse -> inUseImage
        on -> onImage
        else -> null
    }
}

/**
 * The 9900's image for an icon [slot] of Android's, null to keep Android's (notification keys
 * included): its last two-state image in [states] if it has one, else its single image.
 */
fun statusIconImage(slot: String?, states: Map<String, String?> = emptyMap()): String? =
    states[slot] ?: STATUS_ICON_IMAGES[slot]
