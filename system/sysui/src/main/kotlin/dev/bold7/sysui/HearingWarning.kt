package dev.bold7.sysui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.DialogView
import dev.bold7.ui.mnemonic
import java.lang.reflect.Method

/** SafetyWarningDialog, which R8 folded into VolumeDialogImpl's fifth anonymous class on the Q25. */
internal const val SAFE_VOLUME_DIALOG = "com.android.systemui.volume.VolumeDialogImpl\$5"
internal const val CSD_DIALOG = "com.android.systemui.volume.CsdWarningDialog"

/** Android's warnings of a loud headset: the safe media volume's, and the sound dose's (CSD). */
internal enum class HearingWarning { SAFE_VOLUME, CSD }

/** A button of Android's dialog: which one (DialogInterface.BUTTON_*) and its label. */
internal data class HearingButton(val which: Int, val label: String)

/** OS 7's words, from this module's resources. */
internal data class HearingWords(val text: String, val proceed: String, val cancel: String)

/** What OS 7's dialog shows: its text, Android's buttons under their labels, the focused one. */
internal data class HearingFace(val text: String, val buttons: List<HearingButton>, val focused: Int)

/**
 * OS 7's face of Android's warning [kind] ([message] and the buttons [android] shows), or null to
 * leave Android's dialog as it is whenever it is not what is expected.
 *
 * The safe media volume (Yes raises the volume above the safe level, No keeps it): OS 7's warning
 * before Audio Boost, which does the same, with its words and its Proceed and Cancel (sim-341 to
 * sim-343, C); exactly Yes and No, or Android's. The sound dose (Keep listening, Lower volume):
 * OS 7 had none, Android's words and buttons (D). Buttons positive, negative, neutral from the top
 * (the framework's alerts); the focus on the negative, the one that keeps the
 * ears safe (D: OS 7's focus at opening was not seen), else on the first.
 */
internal fun hearingFace(kind: HearingWarning, message: String?, android: List<HearingButton>, os7: HearingWords): HearingFace? {
    val ordered = listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)
        .mapNotNull { which -> android.firstOrNull { it.which == which } }
    val face = when (kind) {
        HearingWarning.SAFE_VOLUME -> {
            if (ordered.map { it.which } != listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE)) return null
            HearingFace(os7.text, listOf(ordered[0].copy(label = os7.proceed), ordered[1].copy(label = os7.cancel)), 0)
        }
        HearingWarning.CSD -> {
            if (message.isNullOrBlank() || ordered.isEmpty()) return null
            HearingFace(message, ordered, 0)
        }
    }
    return face.copy(focused = face.buttons.indexOfFirst { it.which == DialogInterface.BUTTON_NEGATIVE }.coerceAtLeast(0))
}

/**
 * OS 7's dialog over Android's warnings of a loud headset (docs/BBOS7-VISUEL.md §14), after
 * their start(), from SystemUIDialog.onStart, once Android built them: core/ui's DialogView with
 * the question mark of sim-341, over Android's dialog, which stays underneath, hidden, and keeps
 * everything that matters: when it shows, its threshold, its decision, its volume keys (Volume up
 * held past a second to proceed, Volume down to lower), Back and Escape, its dismissal and its
 * timers. A button of ours clicks Android's own button (its listener, then its dismissal): nothing
 * is decided here, and a warning never goes away without one of Android's ways.
 */
internal class HearingWarnings(
    loader: ClassLoader,
    private val images: (Context) -> Images?,
    private val fail: (String, Exception) -> Unit,
) {
    val safeVolumeStart: Method = loader.loadClass(SAFE_VOLUME_DIALOG).getDeclaredMethod("start")
    val csdStart: Method = loader.loadClass(CSD_DIALOG).getDeclaredMethod("start")

    fun dress(dialog: AlertDialog, kind: HearingWarning) {
        val images = images(dialog.context) ?: return
        val message = dialog.findViewById<TextView>(android.R.id.message)?.text?.toString()
        val shown = listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL).mapNotNull { which ->
            dialog.getButton(which)?.takeIf { it.visibility == View.VISIBLE && !it.text.isNullOrBlank() }?.let { HearingButton(which, it.text.toString()) }
        }
        val words = HearingWords(images.text(R.string.hearing_warning), images.text(R.string.hearing_proceed), images.text(R.string.hearing_cancel))
        val face = hearingFace(kind, message, shown, words)
        if (face == null) {
            Log.w(TAG, "hearing warning: $kind left to Android (${shown.map { it.which }})")
            return
        }
        val labels = face.buttons.map { it.label }
        val view = DialogView(images.context(dialog.context), face.text, labels, DialogIcon.QUESTION)
        view.focused = face.focused
        val content = dialog.findViewById<ViewGroup>(android.R.id.content)
        val stock = (0 until content.childCount).map(content::getChildAt)
        val window = dialog.window!!
        val box = window.attributes.width to window.attributes.height
        val background = window.decorView.background

        fun restoreAndroid() {
            content.removeView(view)
            stock.forEach { it.visibility = View.VISIBLE }
            window.setLayout(box.first, box.second)
            window.setBackgroundDrawable(background)
        }

        fun choose(i: Int) {
            val button = dialog.getButton(face.buttons[i].which)
            if (button == null) {
                Log.w(TAG, "hearing warning: Android's button ${face.buttons[i].which} gone, Android's dialog back")
                return restoreAndroid()
            }
            try {
                button.performClick()
            } catch (e: RuntimeException) {
                fail("hearing warning: Android's button failed, Android's dialog back", e)
                restoreAndroid()
            }
        }

        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.defaultFocusHighlightEnabled = false
        // The trackpad's arrows (bold7-trackpad), its click or Enter, a button's letter; anything
        // else (Back, Escape, the volume keys) goes on to Android's dialog.
        view.setOnKeyListener { _, _, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> view.focused = (view.focused - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> view.focused = (view.focused + 1).coerceAtMost(labels.size - 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> choose(view.focused)
                else -> choose(mnemonic(labels, e.displayLabel) ?: return@setOnKeyListener false)
            }
            true
        }
        // A touch on a button chooses it; elsewhere nothing (core/ui's Bold7Dialog, D).
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) view.layout?.buttonAt(e.x, e.y)?.let(::choose)
            true
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener { if (it) view.requestFocus() }
        content.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        stock.forEach { it.visibility = View.INVISIBLE }
        // The box is ours, centred on the whole screen as the power key's: the window takes it,
        // without Android's box (the framework overlay's border_popup) nor its dim (sim-19, C).
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.attributes = window.attributes.apply { fitInsetsTypes = 0 }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        Log.i(TAG, "hearing warning: OS 7's dialog on $kind")
    }
}
