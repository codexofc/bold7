package dev.bold7.contacts

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.LruCache
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.speakOs7Language
import dev.bold7.platform.stopOrphanKeyReleases
import dev.bold7.showcase.avatar
import dev.bold7.showcase.showcaseOn

internal const val TAG = "Bold7/Contacts"

/** A showcase avatar's size, a contact thumbnail's. */
private const val PICTURE = 96

class ContactsApp : Application() {
    val graph by lazy { ContactsGraph(this) }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        speakOs7Language()
        stopOrphanKeyReleases()
        super.onCreate()
    }
}

val Context.graph: ContactsGraph get() = (applicationContext as ContactsApp).graph

/** The objects of the Contacts' process, built by hand (docs/ARCHITECTURE.md §5). */
class ContactsGraph(private val app: Application) {
    /** The provider's reads and writes, the pictures' decoding, Telecom: never the main thread. */
    val io = Handler(HandlerThread("bold7-contacts", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    private val realStore by lazy { AndroidContacts(app) }

    /**
     * The contacts, or the showcase's made-up people while the global setting bold7_showcase is 1
     * (dev.bold7.showcase): asked again at every use, on [io] only.
     */
    val store: ContactStore get() = contactStore(showcaseOn(app), { realStore }) { avatar(app, it, PICTURE) }

    /** The list's pictures, decoded once (66 px each, a few hundred at most). Main thread only. */
    val pictures = LruCache<String, Bitmap>(200)
}
