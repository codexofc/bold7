package dev.bold7.pictures

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.LruCache
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.speakOs7Language
import dev.bold7.platform.stopOrphanKeyReleases
import dev.bold7.showcase.showcaseOn
import java.util.concurrent.Executors

class PicturesApp : Application() {
    val graph by lazy { PicturesGraph(this) }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        speakOs7Language()
        stopOrphanKeyReleases()
        super.onCreate()
    }
}

val Context.graph: PicturesGraph get() = (applicationContext as PicturesApp).graph

/** The objects of Pictures' process, built by hand (docs/ARCHITECTURE.md §5). */
class PicturesGraph(private val app: Application) {
    val main = Handler(Looper.getMainLooper())

    /** MediaStore's reads and the actions' writes: never the main thread. */
    val io = Handler(HandlerThread("bold7-pictures", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)

    /** The thumbnails' decoding, two at a time, never the main thread. */
    val decoder = Executors.newFixedThreadPool(2) { r -> Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "bold7-thumbs") }

    val store = Store(app.contentResolver)

    /** The thumbnails decoded, bounded by their bytes: an eighth of the heap (main thread only). */
    val thumbnails = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    /** The made-up pictures of `--ei rows N`, when the app was opened so (tools/perf.sh); null otherwise. */
    @Volatile var demo: List<Picture>? = null

    /** Every picture: the made-up ones, the showcase's (bold7_showcase, MediaStore never read) or MediaStore's. Off the main thread. */
    fun all(): List<Picture> = demo ?: if (showcaseOn(app)) Store.showcase(System.currentTimeMillis()) else store.all()
}
