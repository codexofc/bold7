package dev.bold7.platform

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import dev.bold7.design.OS7_LOCALE

/**
 * Runs this app's process in OS 7's language ([OS7_LOCALE]), whatever the phone's:
 * Android's own per-app language (LocaleManager, API 33), which it keeps across restarts and
 * applies to the whole process before any activity, service or receiver: the resources of the app
 * and of core/ui, Locale.getDefault(), the formats, the labels of the other apps it loads. Its own
 * language may always be set by an app, no permission; no android:localeConfig, so that Android's
 * Settings offer no other one. Call in every Application.onCreate(), after
 * enableStrictModeIfDebuggable(). The very first start, before Android knew it, is redrawn once in
 * English as Android hands the new configuration over.
 */
// Simplification: one small binder call on the main thread at each start of the process, two the
// first time; the check against Android's own value (not the configuration's locale) also covers
// a phone in en-GB that later changes language.
fun Context.speakOs7Language() {
    val manager = getSystemService(LocaleManager::class.java)
    val os7 = LocaleList(OS7_LOCALE)
    if (manager.applicationLocales != os7) manager.applicationLocales = os7
}
