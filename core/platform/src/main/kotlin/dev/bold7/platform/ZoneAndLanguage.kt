package dev.bold7.platform

import android.app.AlarmManager
import android.content.Context
import android.os.LocaleList
import android.util.Log
import com.android.internal.app.LocalePicker

/**
 * The phone's time zone and language as Options' Date and Time and Language set them
 *, behind an interface of ours: [SystemZoneAndLanguage]
 * on the phone, a fake in the tests. Binder calls: never on the main thread. A change Android
 * refuses answers false, logged.
 */
interface ZoneAndLanguage {
    /** Sets the phone's time zone to [id] (a tz database id). */
    fun setZone(id: String): Boolean

    /** The languages the phone offers, as BCP 47 tags ("en-US", "fr-FR"). */
    fun languages(): List<String>

    /** The phone's language, its first locale's tag. */
    fun language(): String

    /** Makes [tag] the phone's only language. */
    fun setLanguage(tag: String): Boolean
}

/**
 * Android's own, with the launcher's privileges (the privapp allowlist, read in the Q25's dex):
 * - the zone by AlarmManager.setTimeZone (public), SET_TIME_ZONE (signature|privileged|role): in
 *   services.jar, AlarmManagerService sets it at once (setTimeZoneImpl, confidence 100), and sends
 *   TIMEZONE_CHANGED. Android's own picker uses TimeManager's manual suggestion instead, whose
 *   permission (SUGGEST_MANUAL_TIME_AND_ZONE) is signature only;
 * - the language by LocalePicker.updateLocales (hidden, the call of Android's language page):
 *   ActivityManager's updatePersistentConfiguration, CHANGE_CONFIGURATION
 *   (signature|privileged|development|role). The list is LocalePicker.getSupportedLocales, the
 *   languages the system image has.
 */
class SystemZoneAndLanguage(private val context: Context) : ZoneAndLanguage {
    override fun setZone(id: String): Boolean = done("zone") { context.getSystemService(AlarmManager::class.java).setTimeZone(id) }

    override fun languages(): List<String> = LocalePicker.getSupportedLocales(context).map { it.replace('_', '-') }.distinct()

    override fun language(): String = LocalePicker.getLocales().get(0)?.toLanguageTag() ?: "en-US"

    override fun setLanguage(tag: String): Boolean = done("language") { LocalePicker.updateLocales(LocaleList.forLanguageTags(tag)) }

    private inline fun done(what: String, call: () -> Unit): Boolean = try {
        call()
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "$what refused: ${e.message}")
        false
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "$what refused: ${e.message}")
        false
    }

    private companion object {
        const val TAG = "Bold7/Options"
    }
}
