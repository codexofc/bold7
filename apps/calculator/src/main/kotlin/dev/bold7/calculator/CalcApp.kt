package dev.bold7.calculator

import android.app.Application
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.speakOs7Language
import dev.bold7.theme.ThemeImages
import dev.bold7.ui.MENU_IMAGES

class CalcApp : Application() {
    override fun onCreate() {
        enableStrictModeIfDebuggable()
        speakOs7Language()
        super.onCreate()
        // Decoded off the main thread before the page asks for them.
        ThemeImages.of(this).preload(CalcView.IMAGES + MENU_IMAGES)
    }
}
