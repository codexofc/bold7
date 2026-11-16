package dev.bold7.memopad

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.showcase.showcaseOn

class MemoApp : Application() {
    val graph by lazy { MemoGraph(this) }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        super.onCreate()
    }
}

val Context.graph: MemoGraph get() = (applicationContext as MemoApp).graph

/** The objects of the MemoPad's process, built by hand (docs/ARCHITECTURE.md §5). */
class MemoGraph(private val app: Application) {
    /** The database's reads and writes, the images' decoding: never the main thread. */
    val io = Handler(HandlerThread("bold7-memopad", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    private val store by lazy { MemoStore(app) }

    /** The memos, the showcase's while the global setting bold7_showcase is 1 (read here, on [io]). */
    val memos: Memos get() = memosFor(showcaseOn(app)) { store }

    private val prefs by lazy { app.getSharedPreferences("options", Context.MODE_PRIVATE) }

    /** MemoPad Options, read and written on [io] only. */
    var options: MemoOptions
        get() = if (showcaseOn(app)) MemoOptions() else MemoOptions(prefs.getBoolean("confirm_delete", true))
        set(o) {
            if (showcaseOn(app)) return
            prefs.edit().putBoolean("confirm_delete", o.confirmDelete).apply()
        }
}
