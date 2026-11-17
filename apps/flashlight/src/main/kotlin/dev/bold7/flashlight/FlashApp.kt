package dev.bold7.flashlight

import android.app.Application
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.speakOs7Language
import dev.bold7.platform.stopOrphanKeyReleases

class FlashApp : Application() {
    override fun onCreate() {
        enableStrictModeIfDebuggable()
        speakOs7Language()
        stopOrphanKeyReleases()
        super.onCreate()
    }
}
