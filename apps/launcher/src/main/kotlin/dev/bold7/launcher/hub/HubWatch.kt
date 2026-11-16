package dev.bold7.launcher.hub

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.Settings
import android.util.Log
import dev.bold7.hub.data.HubContract
import dev.bold7.showcase.SHOWCASE_SETTING

/**
 * Reads the Hub again when apps/hub's provider changes, while the home shows (the banner's
 * counts); while it does not, a change is only remembered, read when it comes back.
 * Registered and read on [thread].
 */
class HubWatch(private val context: Context, private val feed: HubFeed, private val thread: Handler) {
    @Volatile private var shown = false
    @Volatile private var stale = false
    @Volatile private var observing = false

    private val observer = object : ContentObserver(thread) {
        override fun onChange(selfChange: Boolean) {
            if (shown) feed.reload() else stale = true
        }
    }

    // Registering for another app's provider throws until it is installed (ContentService checks
    // the access, targetSdk 26+): tried again each time the home shows.
    private fun observe() = thread.post {
        if (observing) return@post
        val resolver = context.contentResolver
        resolver.registerContentObserver(Settings.Global.getUriFor(HUB_DEMO_SETTING), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(SHOWCASE_SETTING), false, observer)
        try {
            resolver.registerContentObserver(Uri.parse(HubContract.COUNTS_URI), false, observer)
            observing = true
        } catch (e: SecurityException) {
            Log.w("Bold7/Hub", "Hub changes not observed: ${e.javaClass.name}")
            resolver.unregisterContentObserver(observer)
        }
        feed.reload()
    }

    /** The home's window shown or not. */
    fun shown(visible: Boolean) {
        shown = visible
        if (!visible) return
        if (!observing) observe()
        if (stale) {
            stale = false
            feed.reload()
        }
    }

    fun stop() = thread.post {
        context.contentResolver.unregisterContentObserver(observer)
        observing = false
    }
}
