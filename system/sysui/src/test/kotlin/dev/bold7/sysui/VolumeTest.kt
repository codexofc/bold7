package dev.bold7.sysui

import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VolumeTest {
    // AudioManager.STREAM_* and RINGER_MODE_*.
    private val call = 0
    private val ring = 2
    private val music = 3
    private val notification = 5
    private val silent = 0
    private val vibrate = 1
    private val normal = 2
    private val speaker = VolumeOutput.LOUDSPEAKER

    // sim-108: a ring tone at 11 of 15 shows vol-011.png beside the loudspeaker.
    @Test fun levelShowsTheOutputAndItsBar() =
        assertEquals(VolumeLook("vol_loudspeaker", "vol_011"), volumeLook(ring, level = 11, levelMax = 15, muted = false, ringerMode = normal, output = speaker))

    @Test fun barRoundsUpSoThatAnyLevelShows() {
        assertEquals(listOf("vol_0", "vol_001", "vol_014", "vol_015"), listOf(0, 1, 14, 15).map { volumeLook(music, it, 15, false, normal, speaker).bar })
        assertEquals("vol_001", volumeLook(call, level = 1, levelMax = 25, muted = false, ringerMode = normal, output = speaker).bar)
        assertEquals("vol_008", volumeLook(music, level = 8, levelMax = 16, muted = false, ringerMode = normal, output = speaker).bar)
    }

    @Test fun mutedOrZeroShowsTheCrossedIconAndAnEmptyBar() {
        assertEquals(VolumeLook("vol_headset_mute", "vol_0"), volumeLook(music, level = 9, levelMax = 15, muted = true, ringerMode = normal, output = VolumeOutput.HEADSET))
        assertEquals(VolumeLook("vol_loudspeaker_mute", "vol_0"), volumeLook(music, level = 0, levelMax = 15, muted = false, ringerMode = normal, output = speaker))
    }

    @Test fun ringAndNotificationInVibrateOrSilentAreMuted() {
        for (stream in listOf(ring, notification)) for (mode in listOf(silent, vibrate)) {
            assertEquals(VolumeLook("vol_loudspeaker_mute", "vol_0"), volumeLook(stream, level = 5, levelMax = 7, muted = false, ringerMode = mode, output = speaker))
        }
    }

    @Test fun ringerModeDoesNotTouchOtherStreams() =
        assertEquals(VolumeLook("vol_loudspeaker", "vol_015"), volumeLook(music, level = 15, levelMax = 15, muted = false, ringerMode = silent, output = speaker))

    @Test fun nonsenseLevelsStayInTheBar() {
        assertEquals("vol_015", volumeLook(music, level = 40, levelMax = 15, muted = false, ringerMode = normal, output = speaker).bar)
        assertEquals("vol_0", volumeLook(music, level = 3, levelMax = 0, muted = false, ringerMode = normal, output = speaker).bar)
    }

    // NotificationManager.INTERRUPTION_FILTER via Settings.Global.ZEN_MODE_*: off, priority only, none, alarms only.
    private val zenOff = 0
    private val zenPriority = 1
    private val zenNone = 2
    private val zenAlarms = 3
    private val system = 1
    private val alarm = 4

    // VolumeDialogImpl.updateVolumeRowH: what Android's own rows count as muted by Do Not Disturb.
    @Test fun zenMutesWhatAndroidsRowsMute() {
        val none = ZenPolicy()
        assertEquals(listOf(false, false, false, false), listOf(ring, system, alarm, music).map { zenMuted(it, zenOff, ZenPolicy(true, true, true, true)) })
        assertEquals(listOf(true, true, false, false), listOf(ring, system, alarm, music).map { zenMuted(it, zenAlarms, none) })
        assertEquals(listOf(true, true, true, true), listOf(ring, system, alarm, music).map { zenMuted(it, zenNone, none) })
        assertEquals(listOf(false, false, false, false), listOf(ring, system, alarm, music).map { zenMuted(it, zenPriority, none) })
        assertEquals(listOf(true, false, false, false), listOf(ring, system, alarm, music).map { zenMuted(it, zenPriority, ZenPolicy(disallowRinger = true)) })
        assertEquals(listOf(false, true, false, false), listOf(ring, system, alarm, music).map { zenMuted(it, zenPriority, ZenPolicy(disallowSystem = true)) })
        assertEquals(listOf(false, false, true, false), listOf(ring, system, alarm, music).map { zenMuted(it, zenPriority, ZenPolicy(disallowAlarms = true)) })
        assertEquals(listOf(false, false, false, true), listOf(ring, system, alarm, music).map { zenMuted(it, zenPriority, ZenPolicy(disallowMedia = true)) })
    }

    @Test fun otherStreamsAreNeverZenMuted() =
        assertEquals(false, zenMuted(call, zenNone, ZenPolicy(true, true, true, true)))

    // Android's dialog shows the media row when the active stream has no state (VolumeRow.defaultStream).
    @Test fun streamWithoutStateFallsBackToMedia() {
        assertEquals(ring, hudStream(ring, setOf(ring, music)::contains))
        assertEquals(music, hudStream(-1, setOf(ring, music)::contains))
        assertEquals(music, hudStream(150, setOf(ring, music)::contains))
        assertEquals(null, hudStream(-1, setOf(ring)::contains))
        assertEquals(null, hudStream(ring, emptySet<Int>()::contains))
    }

    // AudioDeviceInfo.TYPE_*.
    private val wiredHeadset = 3
    private val sco = 7
    private val a2dp = 8
    private val builtinSpeaker = 2
    private val earpiece = 1

    @Test fun outputFollowsAndroidsDefaultRoute() {
        assertEquals(VolumeOutput.LOUDSPEAKER, volumeOutput(music, setOf(earpiece, builtinSpeaker)))
        assertEquals(VolumeOutput.HANDSET, volumeOutput(call, setOf(earpiece, builtinSpeaker)))
        assertEquals(VolumeOutput.HEADSET, volumeOutput(music, setOf(builtinSpeaker, wiredHeadset)))
        assertEquals(VolumeOutput.HEADSET, volumeOutput(call, setOf(earpiece, wiredHeadset)))
        assertEquals(VolumeOutput.BLUETOOTH, volumeOutput(music, setOf(builtinSpeaker, wiredHeadset, a2dp)))
        assertEquals(VolumeOutput.BLUETOOTH, volumeOutput(call, setOf(earpiece, sco)))
        // A2DP plays media, not calls; SCO the other way round.
        assertEquals(VolumeOutput.HANDSET, volumeOutput(call, setOf(earpiece, a2dp)))
        assertEquals(VolumeOutput.LOUDSPEAKER, volumeOutput(ring, setOf(builtinSpeaker, sco)))
    }

    // sim-108: vol-bg.png at (183, 199) of 640x480, the loudspeaker at (22, 22) and vol-011.png at
    // (70, 35) inside it.
    @Test fun overlayIsCentredWithTheIconAndBarWhereTheSimulatorDrawsThem() {
        assertEquals(183, (640 - HUD_WIDTH) / 2)
        assertEquals(199, (480 - HUD_HEIGHT) / 2)
        assertEquals(41, BAR_Y + 12 / 2)
        assertEquals(22 to 22, ICON_X to hudIconTop(38))
    }

    @Test fun backgroundIsTheThemeImageSize() {
        val bg = ImageIO.read(File("../../assets-gen/sysui/res/drawable-nodpi/vol_bg.png"))
        assertEquals(HUD_WIDTH to HUD_HEIGHT, bg.width to bg.height)
    }

    // A name without its drawable would throw in SystemUI's process.
    @Test fun everyVolumeImageHasItsDrawable() {
        val names = buildSet {
            add("vol_bg")
            for (output in VolumeOutput.entries) for (level in 0..16) for (muted in listOf(true, false))
                volumeLook(music, level, 15, muted, normal, output).let { add(it.icon); add(it.bar) }
        }
        assertEquals(25, names.size) // the background, 4 icons and their -mute, 16 bars
        assertEquals(emptyList(), names.filterNot { drawableExists(it) })
    }

    // The classes VolumeHud names by strings, in LineageOS 24's SystemUI; VolumeTargets.kt compiles
    // their members, TargetTableTest the table.
    @Test fun targetClassesAreLineages() = ZipFile("../../stubs/systemui.jar").use { jar ->
        for (cls in listOf(VOLUME_LAUNCH, VOLUME_PLUGIN_VIEW_MODEL, VOLUME_VISIBILITY, VOLUME_VISIBLE, VOLUME_CONTROLLER_IMPL, VOLUME_PANEL_STATE, VOLUME_CONTROLLER, VOLUME_STATE, VOLUME_STREAM_STATE)) {
            assertNotNull(jar.getEntry(cls.replace('.', '/') + ".class"), cls)
        }
    }
}

internal fun drawableExists(name: String) =
    java.io.File("../../assets-gen/sysui/res/drawable-nodpi/$name.png").exists()
