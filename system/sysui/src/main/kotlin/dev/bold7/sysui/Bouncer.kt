package dev.bold7.sysui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.bold7.design.DialogColors
import dev.bold7.design.DialogDimens
import dev.bold7.design.Typography
import dev.bold7.ui.DialogLayout
import dev.bold7.ui.focusPaint
import dev.bold7.ui.textPaint
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

// The SystemUI classes Bouncer reaches by name (the module's class loader cannot see them), rows
// of hook-targets.tsv; LockTargets.kt compiles the members.
internal const val KEYGUARD_PIN_VIEW = "com.android.keyguard.KeyguardPINView"
internal const val KEYGUARD_PASSWORD_VIEW = "com.android.keyguard.KeyguardPasswordView"
internal const val PASSWORD_TEXT_VIEW = "com.android.keyguard.PasswordTextView"
internal const val KEYGUARD_PIN_BASED_VIEW = "com.android.keyguard.KeyguardPinBasedInputView"
internal const val KEYGUARD_SECURITY_CONTAINER = "com.android.keyguard.KeyguardSecurityContainer"
internal const val KEYGUARD_SIM_PIN_VIEW = "com.android.keyguard.KeyguardSimPinView"
internal const val KEYGUARD_SIM_PUK_VIEW = "com.android.keyguard.KeyguardSimPukView"
private const val SYSTEMUI_PACKAGE = "com.android.systemui"

/** The PasswordTextView of each PIN view, by its id (getPasswordTextViewId). */
private val ENTRY_IDS = mapOf(KEYGUARD_PIN_VIEW to "pinEntry", KEYGUARD_SIM_PIN_VIEW to "simPinEntry", KEYGUARD_SIM_PUK_VIEW to "pukEntry")

/**
 * The 9900's password entry over Android's bouncer (docs/BBOS7-VISUEL.md §10, sim-85 to sim-89):
 * Android keeps every view, every state and every decision; the code typed never reaches this
 * code. Android's entry (PasswordTextView of the PIN, the EditText of the password) keeps the focus
 * and the keys, holds the code and has it checked (LockPatternUtils, LockSettingsService,
 * Gatekeeper); its messages, attempts and delays are Android's. The cover over it only reads how
 * many characters Android holds and what its message areas say, and draws OS 7's box. Nothing here
 * may send Android down a path this SystemUI never takes.
 *
 * The same box over AOSP's views of the SIM's PIN and PUK: the digits stay in their
 * PasswordTextView, which keeps the focus and the keys; their controllers send them to the modem
 * (supplyIccLockPin, supplyIccLockPuk) when Android's confirm key or OK says so, and their
 * messages and attempts left are Android's, in the box. Only the label is OS 7's, by the view and
 * the prompt Android shows ([simEntry]).
 */
internal class Bouncer(private val images: (Context) -> Images?, private val fail: (String, Exception?) -> Unit) {
    // Weak: Android inflates the bouncer's views again (density, reinflateViewFlipper).
    private val covers = ArrayList<WeakReference<BouncerCover>>()

    /**
     * Whether OS 7's entry is the device's bouncer Android shows or would show: a cover in place
     * over the input view the flipper displays (every other mode, the SIM's included: false, its
     * box stays on Android's black and the lock screen's face under Android's scrims). Main thread.
     */
    fun dressed(): Boolean = covers.any { it.get()?.let { c -> !c.sim && c.dressed() } == true }

    /** Whether it is on screen: [dressed], and Android's bouncer visible (keyguard_bouncer_container). Main thread. */
    fun shown(): Boolean = covers.any { it.get()?.let { c -> !c.sim && c.dressed() && c.isShown } == true }

    /** Whether OS 7's box covers this SIM view now (a tap, a failure or no keyboard: false). Main thread. */
    private fun simCovered(view: Any): Boolean = covers.any { it.get()?.let { c -> c.sim && c.input === view && c.dressed() } == true }

    fun pinTarget(loader: ClassLoader): Method = loader.loadClass(KEYGUARD_PIN_VIEW).getDeclaredMethod("onFinishInflate")

    fun passwordTarget(loader: ClassLoader): Method = loader.loadClass(KEYGUARD_PASSWORD_VIEW).getDeclaredMethod("onFinishInflate")

    fun simTarget(loader: ClassLoader, cls: String): Method = loader.loadClass(cls).getDeclaredMethod("onFinishInflate")

    fun pinKeyTarget(loader: ClassLoader): Method =
        loader.loadClass(KEYGUARD_PIN_BASED_VIEW).getDeclaredMethod("onKeyDown", Int::class.javaPrimitiveType, KeyEvent::class.java)

    /**
     * After KeyguardPINView.onFinishInflate, KeyguardPasswordView.onFinishInflate,
     * KeyguardSimPinView.onFinishInflate or KeyguardSimPukView.onFinishInflate, on the main thread:
     * OS 7's cover on top of the view, with a keyboard at hand ([bouncerCovered]); the device's PIN
     * and password, and the SIM's views ([bouncerDressed]). The 9900 had no pattern: a pattern
     * left from before Bold7 keeps Android's own grid, never covered. Android's views stay
     * where they are, visible under the cover, so that the entry keeps the focus and the keys
     * (BenOS's LockscreenPinEntry still clicks the PIN's keys by their ids). The cover takes every
     * touch, so that no key of the pad answers under it, but the Emergency button's, which clicks
     * Android's own; any other tap steps aside for good (until SystemUI restarts), Android's
     * bouncer back: the way out if the keyboard cannot type the code. Nothing put on when a view
     * is not as expected: Android's bouncer stays.
     */
    fun dress(view: ViewGroup, find: (View, String) -> View?) {
        val viewClass = view.javaClass.name
        val config = view.resources.configuration
        if (!bouncerCovered(viewClass, config.keyboard, config.hardKeyboardHidden)) return
        val images = images(view.context) ?: return
        val sim = simView(viewClass)
        val typed: () -> Int = when (viewClass) {
            KEYGUARD_PIN_VIEW, KEYGUARD_SIM_PIN_VIEW, KEYGUARD_SIM_PUK_VIEW -> {
                val entry = find(view, ENTRY_IDS.getValue(viewClass)) ?: return
                val chars = try {
                    entry.javaClass.getField("mTextChars")
                } catch (e: NoSuchFieldException) {
                    fail("view target missing: ${entry.javaClass.name}.mTextChars, Android's bouncer kept", e)
                    return
                }
                { (chars.get(entry) as? java.util.ArrayList<*>)?.size ?: 0 }
            }
            else -> {
                val entry = find(view, "passwordEntry") as? EditText ?: return
                { entry.length() }
            }
        }
        // Android's message areas: the old one, and the two of BouncerMessageView (A14's revamped
        // messages); whichever Android fills.
        val areas = listOf("bouncer_message_area", "bouncer_primary_message_area", "bouncer_secondary_message_area")
            .mapNotNull { id -> view.resources.getIdentifier(id, "id", SYSTEMUI_PACKAGE).takeIf { it != 0 }?.let { view.findViewById<TextView>(it) } }
        val emergency = view.resources.getIdentifier("emergency_call_button", "id", SYSTEMUI_PACKAGE).takeIf { it != 0 }?.let { view.findViewById<View>(it) }
        val res = view.resources
        fun texts(vararg names: String) = names.mapNotNull { name -> res.getIdentifier(name, "string", SYSTEMUI_PACKAGE).takeIf { it != 0 }?.let(res::getText) }
        val label: () -> String?
        val prompts: List<CharSequence>
        val wrong: List<CharSequence>
        if (sim) {
            val labels = mapOf(
                SimEntry.PIN to images.text(R.string.lock_enter_sim_pin),
                SimEntry.PUK to images.text(R.string.lock_enter_sim_puk),
                SimEntry.NEW_PIN to images.text(R.string.lock_enter_new_sim_pin),
                SimEntry.CONFIRM_PIN to images.text(R.string.lock_confirm_sim_pin),
            )
            // The PUK view's states by the prompt KeyguardSimPukViewController's StateMachine shows.
            val pukPrompts = listOf("kg_puk_enter_puk_hint" to SimEntry.PUK, "kg_puk_enter_pin_hint" to SimEntry.NEW_PIN, "kg_enter_confirm_pin_hint" to SimEntry.CONFIRM_PIN)
                .mapNotNull { (name, entry) -> texts(name).firstOrNull()?.let { it.toString() to entry } }.toMap()
            var last: SimEntry? = null
            label = {
                last = simEntry(viewClass, areas.firstOrNull { it.isShown && !it.text.isNullOrBlank() }?.text, pukPrompts, last)
                last?.let(labels::get)
            }
            // Each state's prompt (the controllers' resetState and StateMachine) is OS 7's label; a
            // wrong code keeps Android's words, which carry the attempts left.
            prompts = texts("kg_sim_pin_instructions", "kg_puk_enter_puk_hint", "kg_puk_enter_pin_hint", "kg_enter_confirm_pin_hint", "keyguard_enter_your_pin")
            wrong = emptyList()
        } else {
            val enter = images.text(R.string.lock_enter_password)
            label = { enter }
            prompts = texts("keyguard_enter_your_pin", "keyguard_enter_your_password", "keyguard_enter_pin", "keyguard_enter_password")
            wrong = texts("kg_wrong_pin", "kg_wrong_password")
        }
        val cover = BouncerCover(view.context, view, sim, images, typed, label, { bouncerText(areas.map { if (it.isShown) it.text else null }, prompts) }, wrong, emergency, fail)
        if (!overlay(view as? LinearLayout ?: return, cover)) return
        covers.removeAll { it.get() == null }
        covers += WeakReference(cover)
        Log.i(TAG, "bouncer: the 9900's entry over Android's ${view.javaClass.simpleName}")
    }

    /**
     * The cover over the whole of [view], a LinearLayout (KeyguardInputView), whose children follow
     * one another: they move, with their own LayoutParams, into a LinearLayout of the same
     * orientation and gravity, under the cover in a frame that becomes [view]'s only child. Android
     * reaches them by their fields and ids, never by [view]'s children (KeyguardPINView,
     * KeyguardPasswordView and their controllers, read in the Q25's dex): its layout, weights and
     * references are unchanged; the focus, if Android has already given it (the bouncer is usually
     * inflated before it shows), goes back where it was. Put back as they were if anything fails.
     *
     * That LinearLayout, ours, does not draw its children while the cover is there: the lock screen
     * shows through, as on sim-85. Nothing of Android's views changes (alpha, visibility and clip
     * stay theirs: isVisibleToUser, which KeyguardPasswordView asks before it takes the focus,
     * still says yes), and Android's own drawing of the bouncer (KeyguardSecurityContainer's, which
     * tells KeyguardViewMediator it is drawn) goes on.
     */
    private fun overlay(view: LinearLayout, cover: BouncerCover): Boolean {
        val children = (0 until view.childCount).map { view.getChildAt(it) }
        val params = children.map { it.layoutParams }
        val focused = view.findFocus()
        try {
            val inner = object : LinearLayout(view.context) {
                override fun dispatchDraw(canvas: Canvas) {
                    if (cover.visibility != View.VISIBLE) super.dispatchDraw(canvas)
                }

                // No focus moves among Android's undrawn views (trackpad arrows, Tab): the entry
                // keeps it, so that no hidden key of the pad gets it and takes Enter or the click
                // as a press of its own digit. Android's again once the cover is given back.
                override fun focusSearch(focused: View?, direction: Int): View? =
                    if (cover.visibility == View.VISIBLE) null else super.focusSearch(focused, direction)
            }.apply {
                orientation = view.orientation
                gravity = view.gravity
                weightSum = view.weightSum
                isBaselineAligned = view.isBaselineAligned
                clipChildren = view.clipChildren
                clipToPadding = view.clipToPadding
            }
            view.removeAllViews()
            children.forEachIndexed { i, child -> inner.addView(child, params[i]) }
            val frame = FrameLayout(view.context).apply {
                clipChildren = view.clipChildren
                clipToPadding = view.clipToPadding
            }
            frame.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cover.inner = inner
            view.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            focused?.requestFocus()
            return true
        } catch (e: RuntimeException) {
            fail("bouncer: cover not put on, Android's bouncer kept", e)
            view.removeAllViews()
            children.forEachIndexed { i, child ->
                (child.parent as? ViewGroup)?.removeView(child)
                view.addView(child, params[i])
            }
            focused?.requestFocus()
            return false
        }
    }

    /**
     * Around KeyguardPinBasedInputView.onKeyDown(int, KeyEvent): Android's own call, with the key
     * code of the number a letter key carries on the Q25's keyboard ([pinKeyCode]); null leaves the
     * call as it came. One key code, nothing kept: Android's path for a number key from there on.
     * The device's PIN, and the SIM's view while OS 7's box covers it ([pinKeysTranslated]).
     */
    fun pinKeyArgs(view: Any, keyCode: Int, event: KeyEvent?): Array<Any?>? {
        if (event == null) return null
        val cls = view.javaClass.name
        if (!pinKeysTranslated(cls, simCovered = simView(cls) && simCovered(view))) return null
        val code = pinKeyCode(keyCode, event.number, event.repeatCount) ?: return null
        return arrayOf(code, event)
    }
}

/**
 * The cover: clear, it takes every touch over Android's input view (none of its keys answers under
 * it) and draws nothing; OS 7's box is drawn over the whole bouncer, in keyguard_bouncer_container's
 * overlay ([Box]), so that it neither fades nor slides with Android's bouncer: it is there as soon
 * as Android's bouncer is visible and gone as soon as its disappearing starts (a right code), as a
 * dialog of OS 7 pops (D: no animation, like the status messages and the volume overlay). The box:
 * "Enter Password:" over its field with one asterisk a character Android holds ([PasswordBox],
 * sim-85, sim-86), the Emergency button under a separator when Android shows its own (sim-65);
 * or, when Android says something else than its prompt and nothing is typed, OS 7's dialog with the
 * exclamation mark, "Incorrect password!" for a wrong code (sim-87), without the OK button: typing
 * goes on in Android's entry (D). The box is centred on the screen's height and on the cover's
 * width (the bouncer is keyguard_security_width wide, centred). [typed], [title] and [message]
 * are read before each frame while attached, never kept beyond; the label is [title]'s ("Enter
 * Password:", or the SIM's by its state), null giving Android's view back.
 *
 * Given back ([giveBack], a tap off the button, or any failure): the cover goes, Android's views
 * draw again over black (the frame's background: the scrims may be clear under it), the box is no
 * longer drawn, and the face and the scrims go back to Android's ([Bouncer.dressed] false).
 */
@SuppressLint("ViewConstructor")
private class BouncerCover(
    context: Context,
    val input: View,
    /** Over a SIM view: kept out of [Bouncer.dressed], its box on Android's black. */
    val sim: Boolean,
    private val images: Images,
    private val typed: () -> Int,
    private val title: () -> String?,
    private val message: () -> String?,
    private val wrong: List<CharSequence>,
    private val emergency: View?,
    private val fail: (String, Exception?) -> Unit,
) : View(context) {
    /** Our LinearLayout holding Android's views, undrawn while the cover is there (Bouncer.overlay). */
    var inner: View? = null

    private var shownTyped = 0
    private var shownLabel: String? = null
    private var shownMessage: String? = null
    private var shownEmergency = false
    private var shownDisappearing = false
    private var broken = false
    private var held = false
    private var downOnButton = false
    private val location = IntArray(2)
    private val containerLocation = IntArray(2)
    private var container: View? = null
    private var securityContainer: View? = null
    private var disappearing: Field? = null
    private val box = Box()

    private val incorrect = images.text(R.string.lock_incorrect_password)
    private val emergencyLabel = images.text(R.string.lock_emergency)

    private val text = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.Text)
    private val field = textPaint(Typography.TEXT, Typography.REGULAR, 0xFF000000.toInt())
    private val label = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonText)
    private val labelFocused = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonTextFocused)
        private val separator = Paint().apply { color = PasswordBox.SEPARATOR_COLOR }
    private val caret = focusPaint(PasswordBox.CARET_HEIGHT)
    private val popup: Drawable = images["ui_popup"]
    private val fieldImage: Drawable = images["ui_find"]
    private val exclamation: Drawable = images["ui_dialog_exclamation"]
    private val button: Drawable = images["ui_button"]
    private val buttonFocused: Drawable = images["ui_button_focus"]
    private val handset: Drawable = images[EMERGENCY_ICON]
    private val emergencyWidth = handset.intrinsicWidth + PasswordBox.ICON_GAP + label.measureText(emergencyLabel)

    // Runs before each frame of the bouncer's window, outside the hooks' guard: nothing escapes.
    // Anything unreadable gives Android's view back: no typing blind behind a box that lags. Only
    // while the cover is shown: the SIM's view stays inflated after the boot, and the window is
    // also the shade's, whose frames need not read a hidden bouncer.
    private val follow = ViewTreeObserver.OnPreDrawListener {
        if (!broken && visibility == VISIBLE && isShown) {
            try {
                val n = typed()
                val l = title()
                val m = message()
                val e = emergency?.visibility == VISIBLE
                val d = disappearing?.getBoolean(securityContainer) == true
                if (l == null) {
                    broken = true
                    post { giveBack("bouncer: no label for Android's ${input.javaClass.simpleName} in its state, Android's view given back", null) }
                } else if (n != shownTyped || l != shownLabel || m != shownMessage || e != shownEmergency || d != shownDisappearing) {
                    shownTyped = n
                    shownLabel = l
                    shownMessage = m
                    shownEmergency = e
                    shownDisappearing = d
                    box.invalidateSelf()
                }
                container?.let { if (box.bounds.width() != it.width || box.bounds.height() != it.height) box.setBounds(0, 0, it.width, it.height) }
            } catch (e: ReflectiveOperationException) {
                broken = true
                post { giveBack("bouncer: Android's entry unreadable, Android's view given back", e) }
            } catch (e: RuntimeException) {
                broken = true
                post { giveBack("bouncer: Android's entry unreadable, Android's view given back", e) }
            }
        }
        true
    }

    init {
        isClickable = true
        isFocusable = false
        // BenOS's LockscreenPinEntry and TalkBack still find Android's views under it.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** In place over the input view the flipper displays (the others are GONE), with its box. */
    fun dressed(): Boolean = visibility == VISIBLE && isAttachedToWindow && input.visibility == VISIBLE && container != null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(follow)
        if (visibility != VISIBLE) return
        val id = resources.getIdentifier("keyguard_bouncer_container", "id", SYSTEMUI_PACKAGE)
        var p = parent
        while (p is View) {
            if (p.javaClass.name == KEYGUARD_SECURITY_CONTAINER) securityContainer = p
            if (id != 0 && p.id == id) {
                container = p
                break
            }
            p = p.parent
        }
        val holder = container
        if (holder == null) {
            giveBack("bouncer: keyguard_bouncer_container not found, Android's bouncer kept", null)
            return
        }
        try {
            disappearing = securityContainer?.javaClass?.getField("mDisappearAnimRunning")
        } catch (e: NoSuchFieldException) {
            fail("bouncer: KeyguardSecurityContainer.mDisappearAnimRunning missing, the box stays until the bouncer hides", e)
        }
        if (securityContainer == null) fail("bouncer: KeyguardSecurityContainer not found, the box stays until the bouncer hides", null)
        box.setBounds(0, 0, holder.width, holder.height)
        holder.overlay.add(box)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(follow)
        container?.overlay?.remove(box)
        container = null
        super.onDetachedFromWindow()
    }

    /** The box, in the cover's x and the screen's y. */
    private fun box() = PasswordBox(width, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels, shownEmergency, emergencyWidth)

    /**
     * A touch that starts on the Emergency button holds it (its blue look) and, released there,
     * clicks Android's own button if Android has it enabled; any other tap gives Android's bouncer
     * back. A slide off the button does neither.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // In the screen's y, as the box.
        getLocationOnScreen(location)
        val y = event.y + location[1]
        val onButton = box().onButton(event.x, y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downOnButton = onButton
                hold(onButton)
            }
            MotionEvent.ACTION_MOVE -> if (held && !onButton) hold(false)
            MotionEvent.ACTION_CANCEL -> hold(false)
            MotionEvent.ACTION_UP -> {
                val button = emergency
                if (held && onButton && button != null && button.isEnabled) {
                    // Android's own button, its own listener: the emergency dialer, nothing unlocked.
                    button.performClick()
                } else if (!downOnButton) {
                    giveBack(null, null)
                    Log.i(TAG, "bouncer: Android's bouncer given back by a tap, until SystemUI restarts")
                }
                hold(false)
            }
        }
        return true
    }

    private fun hold(on: Boolean) {
        if (held == on) return
        held = on
        box.invalidateSelf()
    }

    /** Android's bouncer back for good (until SystemUI restarts): its views drawn again, over black. */
    private fun giveBack(why: String?, e: Exception?) {
        if (why != null) fail(why, e)
        visibility = GONE
        (parent as? View)?.setBackgroundColor(0xFF000000.toInt())
        inner?.invalidate()
        box.invalidateSelf()
    }

    /**
     * OS 7's box, in keyguard_bouncer_container's overlay: drawn after Android's bouncer, at the
     * container's alpha, untouched by the fades and slides Android gives KeyguardSecurityContainer
     * and its input views. Only while the cover is in place and Android's disappearing has not
     * started; the container draws only while Android shows its bouncer.
     */
    private inner class Box : Drawable() {
        override fun draw(canvas: Canvas) {
            if (!dressed() || shownDisappearing || broken) return
            try {
                val holder = container ?: return
                getLocationOnScreen(location)
                holder.getLocationOnScreen(containerLocation)
                canvas.save()
                canvas.translate((location[0] - containerLocation[0]).toFloat(), -containerLocation[1].toFloat())
                val m = shownMessage
                if (m != null && bouncerBox(m, shownTyped) == BouncerBox.ERROR) {
                    drawError(canvas, errorText(m, wrong, incorrect))
                } else {
                    drawEntry(canvas)
                }
                canvas.restore()
            } catch (e: RuntimeException) {
                broken = true
                // Not in the middle of a draw: the cover goes at the next turn of the main thread.
                post { giveBack("bouncer: drawing, Android's bouncer given back", e) }
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun drawEntry(canvas: Canvas) {
        val b = box()
        // border_popup.png, 40x40: its border pixels are its column 4 and 35, its row 2 and 33.
        popup.setBounds(b.left - 4, b.top - 2, b.right + 4, b.top + b.height + 6)
        popup.draw(canvas)
        canvas.drawText(shownLabel ?: return, b.labelX.toFloat(), b.labelBaseline.toFloat(), text)
        fieldImage.setBounds(b.fieldLeft, b.fieldTop, b.fieldRight, b.fieldBottom)
        fieldImage.draw(canvas)
        val mask = maskedEntry(shownTyped, b.maskMax(field.measureText("*")))
        canvas.drawText(mask, b.textX.toFloat(), b.textBaseline.toFloat(), field)
        val x = b.textX + field.measureText(mask)
        canvas.save()
        canvas.translate(x, b.caretTop.toFloat())
        canvas.drawRect(0f, 0f, PasswordBox.CARET_WIDTH.toFloat(), PasswordBox.CARET_HEIGHT.toFloat(), caret)
        canvas.restore()
        if (b.emergency) drawEmergency(canvas, b.separatorLeft, b.separatorY, b.separatorRight, b.buttonLeft, b.buttonTop, b.buttonWidth)
    }

    /** The separator and the Emergency button of sim-65. */
    private fun drawEmergency(canvas: Canvas, separatorLeft: Int, separatorY: Int, separatorRight: Int, buttonLeft: Int, buttonTop: Int, buttonWidth: Int) {
        canvas.drawRect(separatorLeft.toFloat(), separatorY.toFloat(), separatorRight.toFloat(), separatorY + 1f, separator)
        // border_button*.png, 60x102: border pixels at its column 3 and 56, its row 3 and 98.
        val image = if (held) buttonFocused else button
        image.setBounds(buttonLeft - 3, buttonTop - 3, buttonLeft + buttonWidth + 3, buttonTop + DialogDimens.BUTTON_HEIGHT + 3)
        image.draw(canvas)
        var lx = buttonLeft + (buttonWidth - emergencyWidth) / 2
        val iy = buttonTop + (DialogDimens.BUTTON_HEIGHT - handset.intrinsicHeight) / 2
        handset.setBounds(lx.toInt(), iy, lx.toInt() + handset.intrinsicWidth, iy + handset.intrinsicHeight)
        handset.draw(canvas)
        lx += handset.intrinsicWidth + PasswordBox.ICON_GAP
        canvas.drawText(emergencyLabel, lx, (buttonTop + DialogDimens.BUTTON_BASELINE).toFloat(), if (held) labelFocused else label)
    }

    /** OS 7's dialog without button, the exclamation mark at its left (DialogLayout of core/ui, sim-87). */
    private fun drawError(canvas: Canvas, message: String) {
        val d = DialogLayout(message, emptyList(), icon = true, measure = text::measureText, screenWidth = width, screenHeight = resources.displayMetrics.heightPixels)
        popup.setBounds(d.left - 4, d.top - 2, d.left + d.width + 4, d.top + d.height + 6)
        popup.draw(canvas)
        exclamation.setBounds(d.iconX, d.iconY, d.iconX + exclamation.intrinsicWidth, d.iconY + exclamation.intrinsicHeight)
        exclamation.draw(canvas)
        for (i in d.lines.indices) canvas.drawText(d.lines[i], d.textX.toFloat(), d.baseline(i).toFloat(), text)
    }
}
