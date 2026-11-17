package dev.bold7.launcher.keys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Holds the Q25's keyboard layout (res/xml/keyboard_layouts.xml): InputManagerService reads it
 * from the manifest's meta-data and never sends anything here.
 */
class KeyboardLayouts : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
