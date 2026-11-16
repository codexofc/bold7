package dev.bold7.saisie

import android.app.Application
import dev.bold7.platform.enableStrictModeIfDebuggable

class SaisieApp : Application() {
    /** Shared by the input method and Options' provider, one process; its files reached off the main thread. */
    val store by lazy { TypingStore { filesDir } }

    override fun onCreate() {
        enableStrictModeIfDebuggable()
        super.onCreate()
    }
}
