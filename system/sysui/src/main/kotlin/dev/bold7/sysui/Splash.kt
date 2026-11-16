package dev.bold7.sysui

internal const val CLIPBOARD_LISTENER = "com.android.systemui.clipboardoverlay.ClipboardListener"
internal const val STARTING_WINDOW_ALGORITHM = "com.android.wm.shell.startingsurface.phone.PhoneStartingWindowTypeAlgorithm"

// StartingWindowInfo.STARTING_WINDOW_TYPE_*, as PhoneStartingWindowTypeAlgorithm returns them.
internal const val STARTING_NONE = 0
internal const val STARTING_SPLASH = 1
internal const val STARTING_SNAPSHOT = 2
internal const val STARTING_SOLID_SPLASH = 3
internal const val STARTING_LEGACY_SPLASH = 4
internal const val STARTING_WINDOWLESS = 5

/**
 * The starting window Android may add while an app opens, OS 7's way (docs/BBOS7-VISUEL.md §24):
 * the 9900 shows no splash, the app's screen comes when it is drawn (sim-110, C, within the
 * snapshot's delay). Every splash Android would draw (the app's icon, its colour, its legacy
 * window background) becomes none, as for an app that sets windowDisablePreview: the screen
 * before stays until the app draws. The task's snapshot (a recent app brought back) stays: it is
 * the app's own last picture. So does the windowless splash, which no app window replaces.
 */
internal fun startingWindowType(suggested: Int): Int = when (suggested) {
    STARTING_SPLASH, STARTING_SOLID_SPLASH, STARTING_LEGACY_SPLASH -> STARTING_NONE
    else -> suggested
}
