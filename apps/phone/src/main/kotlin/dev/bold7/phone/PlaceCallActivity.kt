package dev.bold7.phone

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.bold7.design.ListColors
import dev.bold7.design.Typography
import dev.bold7.ui.SearchField
import dev.bold7.ui.setContentUnderBar
import dev.bold7.ui.slate
import dev.bold7.ui.status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Place Call under the lock (guide 7.1 p. 61, B: "If your smartphone is locked and you don't want
 * to unlock it, press the Send key > Place Call", then type a number and press Send). Over the lock
 * screen, the Phone screen's band and field only: no call log, no contact, no menu, nothing else
 * that opens (nothing interactive while locked, CONTRIBUTING.md); a name typed dials its keypad digits (D: the guide also searches
 * contacts, which would show them under the lock). Send or the click calls, through Telecom like the
 * Phone screen, an emergency number as any other; Escape erases, then leaves; End (ENDCALL, Android's
 * goHome) brings the home over it, which shows the lock screen. noHistory, and finished once stopped:
 * it never stays behind the lock screen, nor comes back over it after the screen went off (Android
 * keeps a noHistory activity stopped by sleep).
 *
 * Exported behind STATUS_BAR_SERVICE (signature of the platform: SystemUI holds it, no app can), for
 * the lock screen's Send key, SystemUI's (system/sysui PlaceCall.kt).
 */
class PlaceCallActivity : Activity() {
    private val graph get() = (application as PhoneApp).graph
    private lateinit var scope: CoroutineScope
    private lateinit var field: SearchField
    private var state = Dialer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        field = SearchField(this, getString(R.string.find_or_dial)).apply { isFocusable = false }
        val myNumber = TextView(this).apply {
            typeface = slate(Typography.REGULAR, family = Typography.FAMILY_CONDENSED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, PhoneActivity.MY_NUMBER_SIZE)
            setTextColor(0xFFFFFFFF.toInt())
            includeFontPadding = false
            isSingleLine = true
            setPadding(4, 7, 4, 0)
            setBackgroundColor(PhoneActivity.MY_NUMBER_BAND)
            text = getString(R.string.my_number, "")
        }
        setContentUnderBar(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ListColors.Background)
            addView(myNumber, LinearLayout.LayoutParams(match, PhoneActivity.MY_NUMBER_HEIGHT))
            addView(View(this@PlaceCallActivity).apply { setBackgroundColor(PhoneActivity.MY_NUMBER_LINE) }, LinearLayout.LayoutParams(match, 1))
            addView(field, LinearLayout.LayoutParams(match, SearchField.HEIGHT))
        })
        scope = CoroutineScope(SupervisorJob() + graph.main)
        scope.launch { myNumber.text = getString(R.string.my_number, withContext(graph.io) { graph.log.lineNumber() }) }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }


    private fun set(next: Dialer) {
        if (next.typed != state.typed) field.setText(next.typed)
        state = next
    }

    private fun call() {
        val number = state.toCall() ?: return
        scope.launch {
            if (withContext(graph.io) { graph.log.place(number) }) finish() else status(this@PlaceCallActivity, getString(R.string.call_refused))
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (e.action != KeyEvent.ACTION_DOWN) return if (click || code == KeyEvent.KEYCODE_CALL) true else super.dispatchKeyEvent(e)
        when {
            code == KeyEvent.KEYCODE_CALL || click -> if (e.repeatCount == 0) call()
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE -> state.back()?.let(::set) ?: finish()
            code == KeyEvent.KEYCODE_DEL -> set(state.reduce(DialEvent.Erased))
            else -> {
                val c = dialChar(e.number, e.getUnicodeChar(0).toChar(), e.isAltPressed) ?: return super.dispatchKeyEvent(e)
                if (e.repeatCount == 0) set(state.reduce(DialEvent.Typed(c)))
            }
        }
        return true
    }
}
