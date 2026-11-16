package dev.bold7.ui

import android.content.ClipboardManager
import android.text.Selection
import android.view.KeyEvent
import android.widget.EditText

/**
 * The selection entries of a screen's full menu ([TextAct]) for its text fields. Start Selection
 * (sim-150, sim-256, C): from the cursor, the trackpad extends the selection, by character and by
 * line (Android's Selection.extend*, as Shift and the trackpad do in Typing), until Cut or Copy;
 * Escape stops it, the cursor left at the moving end (D). One field at a time.
 */
class FieldSelection {
    private var field: EditText? = null

    /** The entries for [f]. hasPrimaryClip reads nothing of the clipboard (no access notice). */
    fun acts(f: EditText): List<TextAct> =
        textActs(f.hasSelection(), f.context.getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true)

    /** [acts] as the entries of an app's own action enum (see [asEntries]). */
    fun <A : Enum<A>> entries(f: EditText, actions: Array<A>): List<MenuEntry<A>> = acts(f).asEntries(actions)

    /** True from Start Selection until Cut, Copy or Escape. */
    val active get() = field != null

    fun perform(f: EditText, act: TextAct) {
        when (act) {
            TextAct.START_SELECTION -> field = f
            TextAct.CUT -> f.onTextContextMenuItem(android.R.id.cut).also { field = null }
            TextAct.COPY -> f.onTextContextMenuItem(android.R.id.copy).also { field = null }
            TextAct.PASTE -> f.onTextContextMenuItem(android.R.id.paste)
        }
    }

    /** A key down while selecting: the trackpad extends, Escape stops; true when it is taken. */
    fun key(e: KeyEvent): Boolean = key(e.keyCode)

    fun key(code: Int): Boolean {
        val f = field ?: return false
        if (!f.isFocused) {
            field = null
            return false
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            Selection.setSelection(f.text, f.selectionEnd)
            field = null
            return true
        }
        return extend(f, code)
    }

    companion object {
        /** The trackpad key [code] taken as Shift and the trackpad (guide 7.1 p. 37, 38, B): the selection of [f] grows a character or a line; true when [code] is one of the four. */
        fun extend(f: EditText, code: Int): Boolean {
            val layout = f.layout ?: return false
            val text = f.text
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> Selection.extendLeft(text, layout)
                KeyEvent.KEYCODE_DPAD_RIGHT -> Selection.extendRight(text, layout)
                KeyEvent.KEYCODE_DPAD_UP -> Selection.extendUp(text, layout)
                KeyEvent.KEYCODE_DPAD_DOWN -> Selection.extendDown(text, layout)
                else -> return false
            }
            return true
        }

        fun label(act: TextAct) = when (act) {
            TextAct.START_SELECTION -> R.string.ui_start_selection
            TextAct.CUT -> R.string.ui_cut
            TextAct.COPY -> R.string.ui_copy
            TextAct.PASTE -> R.string.ui_paste
        }
    }
}
