package dev.bold7.sysui

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.bold7.ui.DialogView
import java.lang.reflect.Method

internal const val TOAST_FACTORY = "com.android.systemui.toast.ToastFactory"
internal const val SYSTEMUI_TOAST = "com.android.systemui.toast.SystemUIToast"

/**
 * Only the toast Android built from its own text_toast layout, with both its animators, is dressed:
 * replacing them then keeps ToastUI on the paths it already takes (window animations removed, the
 * out animation before the next toast). Anything else stays Android's.
 */
internal fun toastDressable(plugin: Boolean, hasIcon: Boolean, hasText: Boolean, hasInAnimator: Boolean, hasOutAnimator: Boolean) =
    !plugin && hasIcon && hasText && hasInAnimator && hasOutAnimator

/**
 * OS 7's status message on SystemUI's toasts (apps of SDK 30 and up), after ToastFactory.createToast
 * (docs/BBOS7-VISUEL.md §13): the box of a dialog without button, its text in white at the left,
 * centred on the screen (sim-42, C), drawn by core/ui's DialogView as an app's status() does; no
 * app icon, no animation (D, none seen). The view is the screen's size, as status() covers its
 * activity, so that the box wraps and centres as on the 9900; the toast window takes no touch.
 * Android keeps the queue, the durations (2 and 3.5 s; the 9900's, by eye, 2) and the window; the
 * framework overlay keeps it centred.
 */
internal class Toasts(
    loader: ClassLoader,
    private val images: (Context) -> Images?,
    /** SystemUI's diagnostic log, also kept by Vector (SysuiModule.diag). */
    private val diag: (String) -> Unit,
) {
    val createToast: Method = loader.loadClass(TOAST_FACTORY).getDeclaredMethod(
        "createToast", Context::class.java, CharSequence::class.java, String::class.java, Int::class.java, Int::class.java,
    )
    private val toast = loader.loadClass(SYSTEMUI_TOAST)
    private val plugin = toast.getField("mPluginToast")
    private val view = toast.getField("mToastView")
    // Final fields, set once here before ToastUI reads them: accessible for that.
    private val fadeIn = toast.getField("mInAnimator").apply { isAccessible = true }
    private val fadeOut = toast.getField("mOutAnimator").apply { isAccessible = true }
    private var leftToAndroid = false

    fun dress(toast: Any) {
        val root = view.get(toast) as View
        val icon = root.findViewById<View>(root.resources.getIdentifier("icon", "id", "com.android.systemui"))
        val text = root.findViewById<View>(root.resources.getIdentifier("text", "id", "com.android.systemui"))
        if (!toastDressable(plugin.get(toast) != null, icon != null, text != null, fadeIn.get(toast) != null, fadeOut.get(toast) != null)) {
            if (!leftToAndroid) {
                leftToAndroid = true
                diag("toast left to Android: not its text toast (${root.javaClass.name})")
            }
            return
        }
        // text_toast.xml's types; another would throw, counted by the hook.
        val layout = root as LinearLayout
        val message = text as TextView
        val images = images(root.context) ?: return
        val status = DialogView(images.context(root.context), message.text.toString(), emptyList(), null)
        // Both animators or neither, before anything else: Android's in animation sets the text and
        // the icon transparent and scales the whole, its own pair alone undoes it. Ours are instant.
        val androidIn = fadeIn.get(toast)
        fadeIn.set(toast, instant(layout, 1f))
        try {
            fadeOut.set(toast, instant(layout, 0f))
        } catch (e: Exception) {
            fadeIn.set(toast, androidIn)
            throw e // counted by the hook, the toast left Android's
        }
        icon.visibility = View.GONE
        // Visible but of no size: what a screen reader reads of the toast (ToastPresenter's
        // accessibility event) is still its text.
        message.layoutParams = LinearLayout.LayoutParams(0, 0)
        layout.background = null
        layout.elevation = 0f
        layout.setPadding(0, 0, 0, 0)
        val screen = root.resources.displayMetrics
        layout.addView(status, LinearLayout.LayoutParams(screen.widthPixels, screen.heightPixels))
    }

    private fun instant(view: View, alpha: Float): Animator = ObjectAnimator.ofFloat(view, View.ALPHA, alpha).setDuration(0)
}
