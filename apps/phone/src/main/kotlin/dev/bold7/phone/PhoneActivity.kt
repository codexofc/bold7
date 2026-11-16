package dev.bold7.phone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import dev.bold7.design.Typography
import dev.bold7.design.ListColors
import dev.bold7.ui.setContentUnderBar
import dev.bold7.ui.themeImage
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SearchField
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.bb10List
import dev.bold7.ui.slate
import dev.bold7.ui.status
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Phone screen of BBOS 7.1 (sim-141, ps 33246, C; docs/BBOS7-VISUEL.md §26): the band "My
 * Number:", the field "Find Contact or Dial a Number" over the call logs, newest first, "* No
 * Contacts *" when nothing matches what is typed (sim-144). Typing on the keyboard dials, its digit keys
 * typing their digit, Alt their letter (guide p. 63); the log narrows to what matches; Send calls
 * what is typed or the focused call (p. 61), a click calls the focused call. The DIALER role's
 * activity (DIAL, with and without tel:). It renders [Dialer] and decides nothing.
 *
 * During a call it shows the call screen, unless opened by Add Participant ([EXTRA_ADD]).
 *
 * The full menu of sim-142 ([menu]); Call From Contacts and the contacts button at
 * the field's right (sim-141) ask Select Contact of Bold7's Contacts and call the number chosen; 1
 * held calls the voice mail, a letter held its speed dial, or offers to assign it ([held]); Add
 * Pause and Add Wait; Phone Ringtones opens Options' Phone Ringtone.
 *
 * A name typed also finds the contacts' numbers (Dialer.shown, READ_CONTACTS read
 * on the io thread with the log).
 */
class PhoneActivity : Activity() {
    companion object {
        const val EXTRA_ADD = "dev.bold7.phone.ADD"
        const val MY_NUMBER_HEIGHT = 33
        const val MY_NUMBER_SIZE = 21f
        const val MY_NUMBER_BAND = 0xFF191B1E.toInt()
        const val MY_NUMBER_LINE = 0xFF020202.toInt()

        /** Select Contact of Bold7's Contacts (SELECT_CONTACT, signature). */
        const val CONTACTS = "dev.bold7.contacts"
        const val SELECT_CONTACT = "$CONTACTS.SelectContactActivity"

        /** Options' Phone Ringtone, in the launcher (OptionsActivity.EXTRA_SCREEN, Screen.PHONE_TONES). */
        const val LAUNCHER = "dev.bold7.launcher"
        const val OPTIONS = "$LAUNCHER.options.OptionsActivity"
        const val OPTIONS_SCREEN = "dev.bold7.options.SCREEN"
        const val PHONE_TONES = "PHONE_TONES"

        // sim-141 (C): the button 72x53 at 3 px of the right edge, 2 px under the band's top; the
        // field's white ends 83 px before the edge.
        const val BUTTON_W = 72
        const val BUTTON_H = 53
        const val BUTTON_RIGHT = 3
        const val BUTTON_TOP = 2
        const val FIELD_RIGHT = 83

        private const val PICK_CALL = 1
        private const val PICK_SPEED = 2
        private const val STATE_ASSIGNING = "assigning"
    }

    private val graph get() = (application as PhoneApp).graph
    private lateinit var scope: CoroutineScope
    private lateinit var field: SearchField
    private lateinit var list: ListView
    private lateinit var myNumber: TextView
    private lateinit var none: TextView
    private lateinit var menuView: MenuView<PhoneAct>

    private var state = Dialer()
    private var rows: Map<Long, LogRow> = emptyMap()
    private var menu: Menu<PhoneAct>? = null
    private var held = false
    private var adding = false
    private var speed = SpeedDial()

    /** The key whose hold is watched, and what was typed before it (a shortcut only as a number's first key). */
    private var keyDown = 0
    private var typedBefore = ""
    private var keyHeld = false

    /** The speed dial key being assigned while Select Contact is open. */
    private var assigning: Char? = null
    private var dialog: Bold7Dialog? = null
    @Volatile private var icons: Bitmap? = null
    private var callGlyph: Bitmap? = null
    private val adapter = Rows()

    private val observer by lazy {
        object : ContentObserver(graph.ioThread) {
            override fun onChange(selfChange: Boolean) {
                load()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        field = SearchField(this, getString(R.string.find_or_dial)).apply {
            // Typed through dispatchKeyEvent: the trackpad's focus stays on the page (as Messages).
            // Focusable only to show its cursor (sim-141): no soft keyboard, the key events never reach it.
            isFocusableInTouchMode = true
            showSoftInputOnFocus = false
        }
        list = bb10List(ListView(this), 0).apply {
            adapter = this@PhoneActivity.adapter
            isFocusable = false
            isHapticFeedbackEnabled = false
            setOnItemClickListener { _, _, position, _ ->
                update(DialEvent.Moved(position))
                call(state.focused?.number)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                update(DialEvent.Moved(position))
                shortMenu()
                true
            }
        }
        // sim-141: a band of 33 px #191B1E and its #020202 line, "My Number:" in BBAlpha Sans
        // Condensed 21 white at x 4 on a baseline 23 (C).
        myNumber = TextView(this).apply {
            typeface = slate(Typography.REGULAR, family = Typography.FAMILY_CONDENSED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, MY_NUMBER_SIZE)
            setTextColor(0xFFFFFFFF.toInt())
            includeFontPadding = false
            isSingleLine = true
            setPadding(4, 7, 4, 0)
            setBackgroundColor(MY_NUMBER_BAND)
            text = getString(R.string.my_number, "")
        }
        none = TextView(this).apply {
            typeface = slate(Typography.REGULAR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
            setTextColor(ListColors.Title)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 3, 0, 0)
            text = getString(R.string.no_contacts)
            visibility = View.GONE
        }
        menuView = MenuView(
            this,
            glyph = { if (it == PhoneAct.CALL) callGlyph else null },
            label = ::menuLabel,
            onEntry = ::chosen,
            onExpand = { menu?.let { show(it.expand()) } },
            onClose = { show(null) },
        )
        val contacts = ContactsButton(this).apply { setOnClickListener { pickContact(PICK_CALL) } }
        val band = FrameLayout(this).apply {
            // The band behind the field goes on under the button (the field draws its own over it).
            background = themeImage(this@PhoneActivity, "ui_find_background")
            addView(field, FrameLayout.LayoutParams(match, SearchField.HEIGHT).apply { rightMargin = FIELD_RIGHT })
            addView(contacts, FrameLayout.LayoutParams(BUTTON_W, BUTTON_H, Gravity.END or Gravity.TOP).apply {
                rightMargin = BUTTON_RIGHT
                topMargin = BUTTON_TOP
            })
        }
        setContentUnderBar(FrameLayout(this).apply {
            setBackgroundColor(ListColors.Background)
            addView(LinearLayout(this@PhoneActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(myNumber, LinearLayout.LayoutParams(match, MY_NUMBER_HEIGHT))
                addView(View(this@PhoneActivity).apply { setBackgroundColor(MY_NUMBER_LINE) }, LinearLayout.LayoutParams(match, 1))
                addView(band, LinearLayout.LayoutParams(match, SearchField.HEIGHT))
                addView(none, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(list, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        scope = CoroutineScope(SupervisorJob() + graph.main)
        assigning = savedInstanceState?.getChar(STATE_ASSIGNING)?.takeIf { it != '\u0000' }
        scope.launch {
            val (strip, glyph) = withContext(graph.io) { decode(R.drawable.ph_log_icons) to decode(R.drawable.ph_sm_call) }
            icons = strip
            callGlyph = glyph
            contacts.card = withContext(graph.io) { decode(R.drawable.ph_contacts) }
            list.invalidateViews()
            myNumber.text = getString(R.string.my_number, withContext(graph.io) { graph.log.lineNumber() })
        }
        field.requestFocus()
        opened(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        assigning?.let { outState.putChar(STATE_ASSIGNING, it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opened(intent)
    }

    /** DIAL tel:N types N (the DIALER role's contract); Add Participant stays here during the call. */
    private fun opened(intent: Intent) {
        adding = intent.getBooleanExtra(EXTRA_ADD, false)
        val number = intent.data?.takeIf { intent.action == Intent.ACTION_DIAL && it.scheme == "tel" }?.schemeSpecificPart
        if (number != null) set(Dialer(state.log, state.contacts).let { d -> number.fold(d) { s, c -> s.reduce(DialEvent.Typed(c)) } })
    }

    override fun onStart() {
        super.onStart()
        graph.ioThread.post {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            // The contacts the field finds by name.
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            // Opening the Phone clears the home's missed calls indicator (sim-144, C); their rows
            // then take the seen missed call's icon (D: no capture shows the log after a miss).
            graph.log.markMissedSeen()
        }
        load()
        scope.launch { speed = withContext(graph.io) { graph.speedDial.load() } }
    }

    override fun onResume() {
        super.onResume()
        // During a call the Phone shows it (D), unless the second call is being dialled.
        if (!adding && graph.board.value.calls.any { it.live }) startActivity(Intent(this, CallActivity::class.java))
    }

    override fun onStop() {
        graph.ioThread.post { contentResolver.unregisterContentObserver(observer) }
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    /** The log read again, and its rows' texts worked out, off the main thread. */
    private fun load() = scope.launch {
        val (loaded, texts) = withContext(graph.io) {
            val log = graph.log.recent()
            val contacts = graph.log.contacts()
            val zone = ZoneId.systemDefault()
            val withheld = getString(R.string.unknown_number)
            val dayFirst = DateFormat.getDateFormatOrder(this@PhoneActivity).firstOrNull() == 'd'
            val h24 = DateFormat.is24HourFormat(this@PhoneActivity)
            DialEvent.Loaded(log, contacts) to (log + contacts).associate { it.id to logRow(it, zone, withheld, dayFirst, h24) }
        }
        rows = texts
        update(loaded)
    }

    private fun decode(id: Int): Bitmap? = BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })

    // State

    private fun update(e: DialEvent) = set(state.reduce(e))

    private fun set(next: Dialer) {
        val old = state
        state = next
        if (next.typed != old.typed) field.setText(next.typed).also { field.setSelection(field.length()) }
        // The cursor shows while the field holds the focus, or text is being typed in it (sim-141).
        field.isCursorVisible = next.focus == FIELD || next.typed.isNotEmpty()
        if (next.shown != old.shown) adapter.notifyDataSetChanged() else if (next.focus != old.focus) list.invalidateViews()
        none.visibility = if (next.typed.isNotEmpty() && next.shown.isEmpty()) View.VISIBLE else View.GONE
        // The focused row kept in sight.
        if (next.focus >= 0 && (next.focus < list.firstVisiblePosition || next.focus > list.lastVisiblePosition)) list.setSelection(next.focus)
    }

    /** Places the call; refused (CALL_PHONE missing), OS 7's status message says so (D). */
    private fun call(number: String?) {
        if (number.isNullOrEmpty()) return
        scope.launch {
            val placed = withContext(graph.io) { graph.log.place(number) }
            if (!placed) status(this@PhoneActivity, getString(R.string.call_refused)) else set(state.back() ?: state)
        }
    }

    /** Call Voice Mail (guide p. 77): Telecom dials the SIM's voice mail number; none set, OS 7's status message says so (D). */
    private fun callVoiceMail() = scope.launch {
        when (withContext(graph.io) { graph.log.voiceMail() }) {
            VoiceMailResult.PLACED -> set(state.back() ?: state)
            VoiceMailResult.NO_NUMBER -> status(this@PhoneActivity, getString(R.string.no_voice_mail))
            VoiceMailResult.REFUSED -> status(this@PhoneActivity, getString(R.string.call_refused))
        }
    }

    /** Select Contact of Bold7's Contacts for a number (its own choice of number); its answer in [onActivityResult]. */
    private fun pickContact(request: Int) {
        val pick = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI).setClassName(CONTACTS, SELECT_CONTACT)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, request)
        } catch (e: ActivityNotFoundException) {
            status(this, getString(R.string.no_contacts_app))
        } catch (e: SecurityException) {
            status(this, getString(R.string.no_contacts_app))
        }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val key = assigning.takeIf { requestCode == PICK_SPEED }
        if (requestCode == PICK_SPEED) assigning = null
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        scope.launch {
            val picked = withContext(graph.io) { graph.log.picked(uri.toString()) } ?: return@launch
            when (requestCode) {
                // Call From Contacts calls the number chosen (D: the guide only names the entry).
                PICK_CALL -> call(picked.number)
                PICK_SPEED -> if (key != null) {
                    speed = speed.assign(key, picked)
                    val kept = speed
                    withContext(graph.io) { graph.speedDial.save(kept) }
                }
            }
        }
    }

    /** A letter held with nothing typed before it (guide p. 64): its speed dial, the voice mail, or the offer to assign it (D). */
    private fun hold(h: Held) {
        set(state.back() ?: state)
        when (h) {
            Held.VoiceMail -> callVoiceMail()
            is Held.Call -> call(h.number)
            is Held.Assign -> {
                dialog?.dismiss()
                val shown = h.key.uppercaseChar().toString()
                dialog = Bold7Dialog(
                    this, getString(R.string.assign_speed_dial, shown), listOf(getString(R.string.button_yes), getString(R.string.button_no)),
                    DialogIcon.QUESTION, onButton = { if (it == 0) { assigning = h.key; pickContact(PICK_SPEED) } },
                ).also { it.show() }
            }
        }
    }

    /** Phone Ringtones: Options' Phone Ringtone screen, in the launcher; Options: its list. */
    private fun ringtones() = options(PHONE_TONES)

    private fun options(screen: String? = null) {
        try {
            startActivity(Intent().setClassName(LAUNCHER, OPTIONS).apply { screen?.let { putExtra(OPTIONS_SCREEN, it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            status(this, getString(R.string.no_options))
        }
    }

    // Menus: the full menu of sim-142 (Dialer.menu); the short one is Call (sm_call.png, A; its label D).

    private fun menuLabel(a: PhoneAct) = when (a) {
        PhoneAct.CALL -> state.toCall()?.let { getString(R.string.act_call_number, it) } ?: getString(R.string.act_call)
        PhoneAct.ADD_SPEED_DIAL -> getString(R.string.act_add_speed_dial)
        PhoneAct.CALL_FROM_CONTACTS -> getString(R.string.act_call_from_contacts)
        PhoneAct.VOICE_MAIL -> getString(R.string.act_call_voice_mail)
        PhoneAct.VIEW -> getString(R.string.act_view)
        PhoneAct.SPEED_DIAL_LIST -> getString(R.string.act_speed_dial_list)
        PhoneAct.ADD_PAUSE -> getString(R.string.act_add_pause)
        PhoneAct.ADD_WAIT -> getString(R.string.act_add_wait)
        PhoneAct.RINGTONES -> getString(R.string.act_ringtones)
        PhoneAct.SWITCH -> getString(R.string.act_switch_application)
        PhoneAct.STATUS -> getString(R.string.act_status)
        PhoneAct.OPTIONS -> getString(R.string.act_options)
    }

    private fun show(m: Menu<PhoneAct>?) {
        menu = m
        menuView.bind(m)
    }

    private fun shortMenu() {
        if (state.toCall() != null) show(Menu(listOf(MenuEntry(PhoneAct.CALL)), line = 0, highlighted = 0))
    }

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        when (a) {
            PhoneAct.CALL -> call(state.toCall())
            PhoneAct.ADD_SPEED_DIAL -> state.focused?.takeIf { it.number.isNotEmpty() }?.let {
                startActivity(SpeedDialActivity.adding(this, SpeedNumber(it.number, it.name)))
            }
            PhoneAct.CALL_FROM_CONTACTS -> pickContact(PICK_CALL)
            PhoneAct.VOICE_MAIL -> callVoiceMail()
            // D: the submenu in the place of the menu, as the Calendar's View (sim-223).
            PhoneAct.VIEW -> show(Menu(listOf(MenuEntry(PhoneAct.SPEED_DIAL_LIST)), highlighted = 0))
            PhoneAct.SPEED_DIAL_LIST -> startActivity(Intent(this, SpeedDialActivity::class.java))
            PhoneAct.ADD_PAUSE -> update(DialEvent.Typed(PAUSE))
            PhoneAct.ADD_WAIT -> update(DialEvent.Typed(WAIT))
            PhoneAct.RINGTONES -> ringtones()
            PhoneAct.SWITCH -> SwitchApplication.start(this)
            PhoneAct.STATUS -> startActivity(Intent(this, StatusActivity::class.java))
            PhoneAct.OPTIONS -> options()
        }
    }

    // Keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (e.action == KeyEvent.ACTION_UP) {
            if (!click) return super.dispatchKeyEvent(e)
            if (held) held = false else if (menu == null) call(if (state.focus == FIELD) state.toCall() else state.focused?.number)
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        if (code == KeyEvent.KEYCODE_CALL) {
            if (e.repeatCount == 0) call(state.toCall())
            return true
        }
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            when (val r = m.key(
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                    KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                    else -> if (click) MenuKey.CLICK else return true
                },
            )) {
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> show(null)
            }
            return true
        }
        when {
            code == KeyEvent.KEYCODE_DPAD_UP -> update(DialEvent.Step(down = false))
            code == KeyEvent.KEYCODE_DPAD_DOWN -> update(DialEvent.Step(down = true))
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE -> set(state.back() ?: return super.dispatchKeyEvent(e))
            code == KeyEvent.KEYCODE_DEL -> update(DialEvent.Erased)
            code == KeyEvent.KEYCODE_MENU -> show(state.menu())
            click -> if (e.isLongPress || e.repeatCount == 1) {
                held = true
                shortMenu()
            }
            else -> {
                val c = dialChar(e.number, e.getUnicodeChar(0).toChar(), e.isAltPressed) ?: return super.dispatchKeyEvent(e)
                if (e.repeatCount == 0) {
                    keyDown = code
                    keyHeld = false
                    typedBefore = state.typed
                    update(DialEvent.Typed(c))
                } else if (code == keyDown && !keyHeld && (e.isLongPress || e.repeatCount == 1)) {
                    keyHeld = true
                    held(typedBefore, e.getUnicodeChar(0).toChar(), speed)?.let(::hold)
                }
            }
        }
        return true
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = state.shown.size
        override fun getItem(position: Int) = state.shown[position]
        override fun getItemId(position: Int) = state.shown[position].id
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView as? LogRowView ?: LogRowView(this@PhoneActivity) { icons }
            rows[state.shown[position].id]?.let { v.bind(it, position == state.focus) }
            return v
        }
    }
}

/**
 * The contacts button at the right of the field (sim-141, C): address_book_shortcut.png (A) on a
 * grey rounded button drawn by eye from the capture (D: no theme image matches it), a #585B60
 * outline, a #D5D8DB inner line, #D3D3D3 down to #AAACAE. Touched only, never focused (D).
 */
private class ContactsButton(context: Context) : View(context) {
    var card: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF585B60.toInt() }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD5D8DB.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill.shader = LinearGradient(0f, 2f, 0f, h / 3f, 0xFFD3D3D3.toInt(), 0xFFAAACAE.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, RADIUS, RADIUS, outline)
        rect.inset(1f, 1f)
        canvas.drawRoundRect(rect, RADIUS - 1, RADIUS - 1, inner)
        rect.inset(1f, 1f)
        canvas.drawRoundRect(rect, RADIUS - 2, RADIUS - 2, fill)
        card?.let { canvas.drawBitmap(it, (w - it.width) / 2, (h - it.height) / 2, bitmap) }
    }

    private companion object {
        const val RADIUS = 7f
    }
}
