package dev.bold7.ui

import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo

/**
 * The text box of OS 7 for a password (TextDialog's box, "New Password:" of sim-61, C), whose
 * field is a password field: masked, and Typing learns nothing in it. Enter hands the typed
 * characters to [onDone] as a CharArray, then empties the field: the view keeps nothing;
 * [onDone]'s caller zeroes the array once used. Escape, Back or a touch outside cancel
 * ([setOnCancelListener]). Options' Wi-Fi Network and SystemUI's device password.
 */
class PasswordDialog(context: Context, label: String, secure: Boolean, private val onDone: (CharArray) -> Unit) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {
    private val view = TextDialogView(context, label, "") { cancel() }

    init {
        view.field.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = emptyArray()
            useCaret()
            setOnEditorActionListener { _, _, _ ->
                submit()
                true
            }
        }
        setContentView(view)
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // No screenshot nor recent-apps image of the box ([secure]; off for a demonstration's capture only).
            if (secure) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            setWindowAnimations(0)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setTitle(label)
        }
        setOnDismissListener { view.field.text.clear() }
    }

    private fun submit() {
        val text = view.field.text
        // Char by char, never through a String. Not getChars: SDK 37's GetChars inherits it from
        // java.lang.CharSequence, which Kotlin sees as its own CharSequence, without it.
        val chars = CharArray(text.length) { text[it] }
        text.clear()
        dismiss()
        onDone(chars)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) return
        // Options has no status bar (§18): nor has its box.
        window?.insetsController?.hide(WindowInsets.Type.statusBars())
        view.field.requestFocus()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            cancel()
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}
