package dev.bold7.sysui

// The 9900's volume overlay (docs/BBOS7-VISUEL.md §14): volume_overlay.pme of the theme (A), seen
// on the simulator while a ring tone plays (sim-108, C). Pure.

/** Where the sound goes, the four icons of volume_overlay.pme (loudspeaker, headset, handset, bluetooth). */
enum class VolumeOutput(val image: String) {
    LOUDSPEAKER("vol_loudspeaker"),
    HEADSET("vol_headset"),
    HANDSET("vol_handset"),
    BLUETOOTH("vol_bluetooth"),
}

/** What the overlay shows: the output's icon, crossed out when muted, over the bar of the level. */
data class VolumeLook(val icon: String, val bar: String)

/** absoluteVolume15 of volume_overlay.pme: 16 bars, vol-0.png empty then vol-001.png to vol-015.png. */
const val VOLUME_STEPS = 15

// AudioManager.STREAM_VOICE_CALL, STREAM_RING, STREAM_NOTIFICATION, STREAM_BLUETOOTH_SCO, RINGER_MODE_NORMAL.
private const val STREAM_VOICE_CALL = 0
private const val STREAM_RING = 2
private const val STREAM_NOTIFICATION = 5
private const val STREAM_BLUETOOTH_SCO = 6
private const val RINGER_MODE_NORMAL = 2

private const val STREAM_SYSTEM = 1
private const val STREAM_ALARM = 4
private const val STREAM_MUSIC = 3

// Settings.Global.ZEN_MODE_*.
private const val ZEN_PRIORITY = 1
private const val ZEN_NONE = 2
private const val ZEN_ALARMS = 3

/** VolumeDialogController.State's disallow* flags: what Do Not Disturb's priority mode silences. */
data class ZenPolicy(
    val disallowAlarms: Boolean = false,
    val disallowMedia: Boolean = false,
    val disallowRinger: Boolean = false,
    val disallowSystem: Boolean = false,
)

/**
 * Whether Do Not Disturb silences [stream], by the rule of Android's own rows
 * (VolumeDialogImpl.updateVolumeRowH, which shows such a row muted): a muted stream shows the
 * crossed icon over the empty bar here as well, so that the overlay never shows a level that
 * cannot be heard.
 */
fun zenMuted(stream: Int, zenMode: Int, policy: ZenPolicy): Boolean {
    val ring = stream == STREAM_RING
    val system = stream == STREAM_SYSTEM
    return when (zenMode) {
        ZEN_ALARMS -> ring || system
        ZEN_NONE -> ring || system || stream == STREAM_ALARM || stream == STREAM_MUSIC
        ZEN_PRIORITY -> (stream == STREAM_ALARM && policy.disallowAlarms) || (stream == STREAM_MUSIC && policy.disallowMedia) ||
            (ring && policy.disallowRinger) || (system && policy.disallowSystem)
        else -> false
    }
}

/**
 * The stream the overlay shows: Android's active one, or, when it has no state (a remote session
 * not yet reported, no active stream), the media stream, which Android's dialog shows by default
 * (its default row); null when there is no media state either, and Android's dialog stays.
 */
fun hudStream(active: Int, hasState: (Int) -> Boolean): Int? = when {
    hasState(active) -> active
    hasState(STREAM_MUSIC) -> STREAM_MUSIC
    else -> null
}

/** The image of [step] of [VOLUME_STEPS]. */
fun volumeBar(step: Int): String = if (step <= 0) "vol_0" else "vol_%03d".format(step.coerceAtMost(VOLUME_STEPS))

/**
 * The look for Android's stream that changed. Any level above zero lights a step (rounded up).
 * Muted, at zero, or a ring or notification stream in vibrate or silent: the output's -mute icon
 * over the empty bar (in_volumeMuted of volume_overlay.pme, A; D for vibrate and silent, which
 * OS 7 left to the profiles).
 */
fun volumeLook(stream: Int, level: Int, levelMax: Int, muted: Boolean, ringerMode: Int, output: VolumeOutput): VolumeLook {
    val silenced = (stream == STREAM_RING || stream == STREAM_NOTIFICATION) && ringerMode != RINGER_MODE_NORMAL
    if (muted || silenced || level <= 0 || levelMax <= 0) return VolumeLook(output.image + "_mute", volumeBar(0))
    return VolumeLook(output.image, volumeBar((VOLUME_STEPS * level + levelMax - 1) / levelMax))
}

// AudioDeviceInfo.TYPE_*: the outputs that change the icon.
private const val TYPE_WIRED_HEADSET = 3
private const val TYPE_WIRED_HEADPHONES = 4
private const val TYPE_BLUETOOTH_SCO = 7
private const val TYPE_BLUETOOTH_A2DP = 8
private const val TYPE_USB_HEADSET = 22
private const val TYPE_HEARING_AID = 23
private const val TYPE_BLE_HEADSET = 26
private const val TYPE_BLE_SPEAKER = 27

/**
 * The output of [stream] given the connected output devices' [types] (AudioDeviceInfo.getType),
 * as Android routes it by default: a Bluetooth device first, then a wired one; a call without
 * them is the handset, anything else the loudspeaker.
 * Simplification: the speakerphone during a call shows the handset (D); following it needs the
 * communication device, a binder call per change.
 */
fun volumeOutput(stream: Int, types: Set<Int>): VolumeOutput {
    val call = stream == STREAM_VOICE_CALL || stream == STREAM_BLUETOOTH_SCO
    val bluetooth = if (call) setOf(TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET, TYPE_HEARING_AID)
    else setOf(TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_HEARING_AID)
    return when {
        types.any { it in bluetooth } -> VolumeOutput.BLUETOOTH
        types.any { it == TYPE_WIRED_HEADSET || it == TYPE_WIRED_HEADPHONES || it == TYPE_USB_HEADSET } -> VolumeOutput.HEADSET
        call -> VolumeOutput.HANDSET
        else -> VolumeOutput.LOUDSPEAKER
    }
}

/** vol-bg.png, 273x82 with its 1 px clear edge and shadow row, centred on the screen (sim-108: x 183, y 199 of 640x480, C). */
const val HUD_WIDTH = 273
const val HUD_HEIGHT = 82

/** The bar, 180x12, at (70, 35) of vol-bg.png (sim-108, C). */
const val BAR_X = 70
const val BAR_Y = 35

/**
 * The icons' left in vol-bg.png, and the top of one [height] px high: the loudspeaker (23x38) at
 * (22, 22) (sim-108, C), so its middle at 41, the bar's. The other icons, never seen, keep that
 * left and middle (D).
 */
const val ICON_X = 22

fun hudIconTop(height: Int): Int = 41 - height / 2
