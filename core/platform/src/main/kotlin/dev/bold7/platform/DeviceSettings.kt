package dev.bold7.platform

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.util.Log

/** The three tones Android keeps system-wide: the phone's ringtone, the notifier, the reminders (alarm). */
enum class ToneKind { RINGTONE, NOTIFICATION, ALARM }

/** A ringtone as Android's picker lists it: its title (the 9900's) and its content uri. */
data class Tone(val title: String, val uri: String)

/**
 * The settings Options reads and writes, behind an interface of ours
 *: [SystemDeviceSettings] on the phone, a fake in the tests. Every call
 * goes to SettingsProvider or MediaStore over binder: never on the main thread. A write Android
 * refuses answers false, logged.
 */
interface DeviceSettings {
    /** Settings.Global [name], null if unset. */
    fun global(name: String): String?

    fun putGlobal(name: String, value: String): Boolean

    /** Settings.System [name], null if unset. */
    fun system(name: String): String?

    fun putSystem(name: String, value: String): Boolean

    /** Settings.Secure [name], null if unset (writable with WRITE_SECURE_SETTINGS, like the other two). */
    fun secure(name: String): String?

    fun putSecure(name: String, value: String): Boolean

    /** The phone's tones of [kind], the one in use first if the list lacks it. */
    fun ringtones(kind: ToneKind = ToneKind.RINGTONE): List<Tone>

    /** The tone of [kind] in use (its content uri), null if silent. */
    fun ringtone(kind: ToneKind = ToneKind.RINGTONE): String?

    fun setRingtone(uri: String, kind: ToneKind = ToneKind.RINGTONE): Boolean
}

/**
 * Android's own: Settings.Global and Settings.System through WRITE_SECURE_SETTINGS, which
 * SettingsProvider takes for the System table too (mutateSystemSetting checks it before the
 * WRITE_SETTINGS app-op); the ringtone through RingtoneManager, which also rewrites the cache
 * MediaPlayer plays first (a bare `settings put system ringtone` would leave the old one there).
 */
class SystemDeviceSettings(private val context: Context) : DeviceSettings {
    private val resolver get() = context.contentResolver

    override fun global(name: String): String? = Settings.Global.getString(resolver, name)

    override fun putGlobal(name: String, value: String) = written(name) { Settings.Global.putString(resolver, name, value) }

    override fun system(name: String): String? = Settings.System.getString(resolver, name)

    override fun putSystem(name: String, value: String) = written(name) { Settings.System.putString(resolver, name, value) }

    override fun secure(name: String): String? = Settings.Secure.getString(resolver, name)

    override fun putSecure(name: String, value: String) = written(name) { Settings.Secure.putString(resolver, name, value) }

    override fun ringtones(kind: ToneKind): List<Tone> {
        val manager = RingtoneManager(context).apply { setType(type(kind)) }
        val tones = ArrayList<Tone>()
        manager.cursor.use { c ->
            while (c.moveToNext()) tones += Tone(c.getString(RingtoneManager.TITLE_COLUMN_INDEX), manager.getRingtoneUri(c.position).toString())
        }
        val current = ringtone(kind) ?: return tones
        if (tones.none { it.uri == current }) {
            val title = RingtoneManager.getRingtone(context, Uri.parse(current))?.getTitle(context) ?: current
            tones.add(0, Tone(title, current))
        }
        return tones
    }

    override fun ringtone(kind: ToneKind): String? = RingtoneManager.getActualDefaultRingtoneUri(context, type(kind))?.toString()

    override fun setRingtone(uri: String, kind: ToneKind) = written(kind.name) {
        RingtoneManager.setActualDefaultRingtoneUri(context, type(kind), Uri.parse(uri))
        true
    }

    private fun type(kind: ToneKind) = when (kind) {
        ToneKind.RINGTONE -> RingtoneManager.TYPE_RINGTONE
        ToneKind.NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
        ToneKind.ALARM -> RingtoneManager.TYPE_ALARM
    }

    private inline fun written(name: String, put: () -> Boolean): Boolean = try {
        put().also { if (!it) Log.w(TAG, "$name not written") }
    } catch (e: SecurityException) {
        Log.w(TAG, "$name refused", e)
        false
    }

    private companion object {
        const val TAG = "Bold7/Options"
    }
}
