package dev.bold7.flashlight

import android.app.Application
import dev.bold7.platform.enableStrictModeIfDebuggable

class FlashApp : Application() {
    override fun onCreate() {
        enableStrictModeIfDebuggable()
        super.onCreate()
    }
}
