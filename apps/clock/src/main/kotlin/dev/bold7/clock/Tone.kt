package dev.bold7.clock

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log
import java.io.File

/** The tones' folder in the module's layer (system/module/sounds.tsv), readable before the first unlock. */
private const val ALARMS_DIR = "/product/media/audio/alarms"

/** The alarm stream (USAGE_ALARM): the ringer's silent and vibrate modes leave it alone (RingService). */
val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

/**
 * The first source of [tone] that plays, looping at [gain] on [ALARM_ATTRIBUTES]: the module's file,
 * else Android's default alarm, else the first of /system/media/audio/alarms; null when none does
 * (the vibrator alone), each failure logged. The ring and Options' Try it share it.
 */
fun openTone(context: Context, tone: String, gain: Float): MediaPlayer? {
    val sources = listOf<(MediaPlayer) -> Unit>(
        { it.setDataSource("$ALARMS_DIR/$tone") },
        { it.setDataSource(context, RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM) ?: error("no default alarm")) },
        { it.setDataSource(File("/system/media/audio/alarms").listFiles()?.minOrNull()?.path ?: error("no system alarm")) },
    )
    for ((i, source) in sources.withIndex()) {
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(ALARM_ATTRIBUTES)
            source(p)
            p.isLooping = true
            p.setVolume(gain, gain)
            p.prepare()
            p.start()
            return p
        } catch (e: Exception) {
            // Any failure of a source (missing file, locked storage, codec) moves to the next one.
            Log.e(TAG, "alarm tone source $i failed", e)
            p.release()
        }
    }
    Log.e(TAG, "no alarm tone could play: vibrator only")
    return null
}
