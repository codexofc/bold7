package dev.bold7.help

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.speakOs7Language
import dev.bold7.platform.stopOrphanKeyReleases

class HelpApp : Application() {
    /** Files read and pages prepared off the main thread. */
    val io: Handler by lazy { Handler(HandlerThread("bold7-help", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper) }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        speakOs7Language()
        stopOrphanKeyReleases()
        super.onCreate()
    }
}
