package dev.bold7.sysui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.widget.ImageView
import java.lang.reflect.Method

internal const val GLOBAL_ACTIONS_IMPL = "com.android.systemui.globalactions.GlobalActionsImpl"

/** osicon_hourglass.gif of the theme (A), OS 7's busy clock: 35x35, 12 frames of 100 ms. */
internal const val SHUTDOWN_BUSY = "shutdown_busy"

/**
 * Whether OS 7's screen replaces Android's while the phone powers off or restarts, and Android's
 * words it keeps for a screen reader (the framework's string, by name), as GlobalActionsImpl
 * chooses them; null for a restart into recovery (factory reset, update), where Android's screen
 * says what is going on.
 */
internal fun shutdownScreenText(isReboot: Boolean, reason: String?): String? = when {
    reason != null && reason.startsWith("recovery") -> null
    isReboot -> "reboot_to_reset_message"
    else -> "shutdown_progress"
}

/**
 * The screen while the phone powers off or restarts, in place of GlobalActionsImpl.showShutdownUi
 * (docs/BBOS7-VISUEL.md §23): black, OS 7's busy clock in the middle (D: the 9900's was never
 * captured), no words, no animation in or out. system_server's ShutdownThread asks SystemUI for
 * this screen and goes on without waiting for it: nothing here delays the shutdown, and whatever
 * fails leaves Android's own screen, which the hook then runs. The window is Android's, with its
 * type, size and flags.
 */
internal class ShutdownScreen(loader: ClassLoader, private val images: (Context) -> Images?) {
    private val impl = loader.loadClass(GLOBAL_ACTIONS_IMPL)
    val showShutdownUi: Method = impl.getDeclaredMethod("showShutdownUi", Boolean::class.java, String::class.java)
    private val context = impl.getField("mContext")

    /** False: Android's screen, as it is. A throw is counted by the hook, Android's screen then. */
    fun show(globalActions: Any, isReboot: Boolean, reason: String?): Boolean {
        val words = shutdownScreenText(isReboot, reason) ?: return false
        val context = context.get(globalActions) as Context
        val images = images(context) ?: return false
        val res = context.resources
        val theme = res.getIdentifier("Theme.SystemUI.Dialog.GlobalActions", "style", "com.android.systemui")
        check(theme != 0) { "shutdown: no Theme.SystemUI.Dialog.GlobalActions" }
        val text = context.getString(res.getIdentifier(words, "string", "android"))
        val busy = images[SHUTDOWN_BUSY]

        val dialog = Dialog(context, theme)
        val window = dialog.window!!
        window.requestFeature(Window.FEATURE_NO_TITLE)
        // Android's window (GlobalActionsImpl.showShutdownUi on the Q25): the whole screen, over
        // the status bar and the keyguard.
        window.attributes.apply {
            @Suppress("DEPRECATION")
            systemUiVisibility = systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            width = LayoutParams.MATCH_PARENT
            height = LayoutParams.MATCH_PARENT
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        window.setType(TYPE_VOLUME_OVERLAY)
        window.clearFlags(LayoutParams.FLAG_DIM_BEHIND)
        @Suppress("DEPRECATION")
        window.addFlags(
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_INSET_DECOR or LayoutParams.FLAG_NOT_TOUCH_MODAL or
                LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or LayoutParams.FLAG_SHOW_WHEN_LOCKED or LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.setWindowAnimations(0)
        window.setTitle(text)
        val image = ImageView(context).apply {
            setImageDrawable(busy)
            contentDescription = text
        }
        dialog.setContentView(FrameLayout(context).apply { addView(image, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)) })
        dialog.setCancelable(false)
        dialog.show()
        // An animated GIF comes out of Resources as an AnimatedImageDrawable, drawn by the render
        // thread; anything else would stay on its first frame.
        (busy as? Animatable)?.start()
        Log.i(TAG, "shutdown: OS 7's screen (reboot=$isReboot, ${if (busy is Animatable) "busy clock turning" else "busy clock still"})")
        return true
    }

    private companion object {
        /** WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY, hidden from the SDK: Android's type for this window. */
        const val TYPE_VOLUME_OVERLAY = 2020
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
