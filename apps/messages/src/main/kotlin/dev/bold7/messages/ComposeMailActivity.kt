package dev.bold7.messages

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import dev.bold7.design.Durations
import dev.bold7.design.Focus
import dev.bold7.design.ListColors
import dev.bold7.design.Typography
import dev.bold7.hub.data.MailResult
import dev.bold7.showcase.showcaseOn
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.FieldSelection
import dev.bold7.ui.useCaret
import dev.bold7.ui.textAct
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.ListItemView
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.bb10List
import dev.bold7.ui.fillPaint
import dev.bold7.ui.slate
import dev.bold7.ui.status
import dev.bold7.ui.themeImage
import dev.bold7.ui.themeBitmap

/**
 * Compose Email (sim-46, sim-47, C; guide 7.1 p. 90-93, B): full screen, a grey band
 * with the send button (send_email.png in border_button, A) at its right, greyed until the mail
 * can leave; the box of To and Cc, the box of Subject, the box of the text. A name typed in To or
 * Cc proposes the contacts' addresses under the box (the same rule as To); the trackpad goes
 * down into them, its click takes one. Menu: Send (focused when it can), Save Draft, the focused field's Start Selection (or Cut and Copy) and Paste (core/ui FieldSelection), Close.
 * Escape with changes: "Changes made!", Save, Discard, Cancel (sim-47, C). With several
 * accounts, a "Send Using:" line chooses the one it leaves from (D: no capture).
 *
 * Nothing written is ever lost: the mail is kept as a draft before it goes to apps/hub, removed
 * once sent; a send that fails says why and keeps the screen as it was, the draft in the inbox.
 *
 * Extras: [EXTRA_DRAFT] a kept draft's id, [EXTRA_PREFILL] a reply or a forward (MailActivity),
 * [EXTRA_TO] and [EXTRA_TITLE] an address and its name (Email from Contacts, the ComposeEmail
 * alias, signature permission), [EXTRA_BODY] the body's first text (Send Contact Card).
 */
class ComposeMailActivity : Activity() {
    companion object {
        const val EXTRA_DRAFT = "draft"
        const val EXTRA_PREFILL = "prefill"
        const val EXTRA_TO = "to"
        const val EXTRA_TITLE = "title"
        /** The text the body starts with (Send Contact Card of Contacts). */
        const val EXTRA_BODY = "body"

        /** sim-46 (C): the band 60 px, the button 86x51, 3 px from its top and 6 from the right edge. */
        const val BAND = 60
        const val BUTTON_TOP = 3
        const val BUTTON_RIGHT = 6
        const val BUTTON_WIDTH = 86
        const val BUTTON_HEIGHT = 51
        /** The boxes 4 px from the sides, a 1 px border, 5 px apart (sim-46, C); a field's line 35 px, its label at x 10. */
        const val SIDE = 4
        const val GAP = 5
        const val LINE = 35
        const val LABEL_X = 10
    }

    private val graph get() = (application as MessagesApp).graph
    private var accounts: List<MailAccountInfo> = emptyList()
    private var contacts: List<EmailContact> = emptyList()
    private var original = MailDraft(account = null)
    private var draft = MailDraft(account = null)
    private var sending = false
    private var menu: Menu<ComposeAct>? = null
    private val selection = FieldSelection()
    private var proposals: List<Addr> = emptyList()
    private var pick = -1
    private var proposing: EditText? = null
    private var binding = false
    private val glyphs = HashMap<ComposeAct, Bitmap?>()

    private lateinit var sendButton: SendButton
    private lateinit var using: TextView
    private lateinit var to: EditText
    private lateinit var cc: EditText
    private lateinit var subject: EditText
    private lateinit var text: EditText
    private lateinit var proposalList: ListView
    private lateinit var menuView: MenuView<ComposeAct>
    private val proposalAdapter = ProposalAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The showcase writes no email: a real account would send it (Contacts' Email). Android's
        // settings cache makes the read a memory one.
        if (showcaseOn(this)) return finish()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        sendButton = SendButton(this).apply { setOnClickListener { send() } }
        using = label("").apply {
            isFocusable = true
            isClickable = true
            setOnClickListener { chooseAccount() }
            visibility = View.GONE
        }
        to = field(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        cc = field(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        subject = field(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        text = field(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            isSingleLine = false
            gravity = Gravity.TOP or Gravity.START
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setLineHeight(LINE)
        }
        proposalList = ListView(this).apply {
            bb10List(this, Focus.RowBottom)
            adapter = proposalAdapter
            visibility = View.GONE
            setOnItemClickListener { _, _, position, _ -> take(position) }
        }
        menuView = MenuView(this, glyph = { glyphs[it] }, label = ::label, onEntry = ::chosen, onExpand = {}, onClose = { show(null) })

        val addresses = MailBoxLayout(this).apply {
            addView(using, LinearLayout.LayoutParams(match, LINE))
            addView(row(R.string.mail_to, to, bold = false), LinearLayout.LayoutParams(match, wrap))
            addView(row(R.string.mail_cc, cc, bold = false), LinearLayout.LayoutParams(match, wrap))
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(FrameLayout(this@ComposeMailActivity).apply {
                addView(sendButton, FrameLayout.LayoutParams(BUTTON_WIDTH, BUTTON_HEIGHT, Gravity.END).apply {
                    topMargin = BUTTON_TOP
                    rightMargin = BUTTON_RIGHT
                })
            }, LinearLayout.LayoutParams(match, BAND))
            addView(addresses, boxParams(wrap))
            addView(MailBoxLayout(this@ComposeMailActivity).apply { addView(row(R.string.mail_subject, subject, bold = true), LinearLayout.LayoutParams(match, wrap)) }, boxParams(wrap))
            addView(MailBoxLayout(this@ComposeMailActivity).apply { addView(text, LinearLayout.LayoutParams(match, match)) }, boxParams(0).apply { weight = 1f })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(MailGrey)
            addView(page)
            addView(proposalList, FrameLayout.LayoutParams(match, wrap).apply {
                leftMargin = SIDE
                rightMargin = SIDE
            })
            addView(menuView)
        })
        // The proposals open under the box of To and Cc.
        addresses.addOnLayoutChangeListener { v, _, _, _, bottom, _, _, _, _ ->
            (proposalList.layoutParams as FrameLayout.LayoutParams).topMargin = bottom
            proposalList.requestLayout()
        }
        listOf(to, cc, subject, text).forEach { f ->
            f.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) = edited(f)
            })
        }
        listOf(to, cc).forEach { f -> f.setOnFocusChangeListener { _, _ -> propose() } }

        val g = graph
        val draftId = intent.getStringExtra(EXTRA_DRAFT)
        val prefill = intent.getStringExtra(EXTRA_PREFILL)
        val address = intent.getStringExtra(EXTRA_TO)
        val name = intent.getStringExtra(EXTRA_TITLE)
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        g.ioThread.post {
            val list = g.mail.accounts()?.list.orEmpty()
            val people = emailContacts(this)
            val kept = draftId?.let(g.drafts::get)
            val images = mapOf(ComposeAct.SEND to "msg_sm_chat_send", ComposeAct.SAVE_DRAFT to "msg_sm_save_draft")
                .mapValues { themeBitmap(applicationContext, it.value) }
            val send = themeBitmap(applicationContext, "msg_send_email")
            runOnUiThread {
                glyphs.putAll(images)
                sendButton.glyph = send
                accounts = list
                contacts = people
                if (list.isEmpty()) {
                    status(this, getString(R.string.mail_no_account))
                    sendButton.postDelayed({ finish() }, Durations.STATUS_MS)
                    return@runOnUiThread
                }
                val start = kept ?: prefill?.let(::draftOf)
                    ?: MailDraft(account = null, to = address?.let { formatAddress(Addr(it, name)) + ", " }.orEmpty(), text = body)
                val asked = intent.getStringExtra(EXTRA_MAIL_ACCOUNT)
                val account = (start.account ?: asked)?.takeIf { a -> list.any { it.id == a } } ?: list.first().id
                original = start.copy(account = account)
                bind(original)
            }
        }
    }

    private fun boxParams(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
        leftMargin = SIDE
        rightMargin = SIDE
        bottomMargin = GAP
    }

    private fun label(text: String, bold: Boolean = false) = TextView(this).apply {
        typeface = slate(if (bold) Typography.BOLD else Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setPadding(LABEL_X, 0, LABEL_X, 0)
        this.text = text
    }

    /** "To:" and its field on one line; the label regular, Subject's bold (sim-46, C). */
    private fun row(labelText: Int, f: EditText, bold: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label(getString(labelText), bold).apply { setPadding(LABEL_X, 0, 4, 0) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, LINE))
        addView(f, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun field(variation: Int) = EditText(this).apply {
        typeface = slate(Typography.REGULAR)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
        setTextColor(ListColors.Title)
        includeFontPadding = false
        background = null
        setPadding(0, 0, LABEL_X, 0)
        minHeight = LINE
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        // sim-46's cursor: a blue bar (C), as the other fields of Bold7.
        useCaret()
        inputType = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        defaultFocusHighlightEnabled = false
    }

    private fun bind(d: MailDraft) {
        binding = true
        draft = d
        to.setText(d.to)
        cc.setText(d.cc)
        subject.setText(d.subject)
        text.setText(d.text)
        binding = false
        showAccount()
        sendButton.ready = d.sendable
        // A reply or a forward writes above the original (S); a new mail starts in To (sim-46, C).
        if (d.to.isNotBlank()) {
            text.requestFocus()
            text.setSelection(0)
        } else {
            to.requestFocus()
        }
    }

    private fun showAccount() {
        using.visibility = if (accounts.size > 1) View.VISIBLE else View.GONE
        using.text = getString(R.string.mail_send_using) + " " + accounts.firstOrNull { it.id == draft.account }?.address.orEmpty()
    }

    private fun chooseAccount() {
        if (accounts.size < 2) return
        Bold7Dialog(this, getString(R.string.mail_send_using), accounts.map { it.address }, onButton = { i ->
            draft = draft.copy(account = accounts[i].id)
            showAccount()
        }).show()
    }

    private fun edited(f: EditText) {
        if (binding) return
        draft = draft.copy(to = to.text.toString(), cc = cc.text.toString(), subject = subject.text.toString(), text = text.text.toString())
        sendButton.ready = draft.sendable && !sending
        if (f === to || f === cc) propose()
    }

    /** The proposals for what is typed in the focused To or Cc. */
    private fun propose() {
        val f = listOf(to, cc).firstOrNull { it.hasFocus() }
        proposing = f
        proposals = f?.let { emailSuggestions(contacts, it.text.toString()) }.orEmpty()
        pick = -1
        proposalList.visibility = if (proposals.isEmpty()) View.GONE else View.VISIBLE
        proposalAdapter.notifyDataSetChanged()
    }

    private fun take(position: Int) {
        val f = proposing ?: return
        val chosen = proposals.getOrNull(position) ?: return
        f.setText(withChosen(f.text.toString(), chosen))
        f.setSelection(f.text.length)
        propose()
    }

    private fun label(a: ComposeAct) = getString(
        when (a) {
            ComposeAct.SEND -> R.string.mail_send
            ComposeAct.SAVE_DRAFT -> R.string.mail_save_draft
            ComposeAct.CLOSE -> R.string.mail_close
            ComposeAct.SWITCH -> R.string.act_switch_application
            ComposeAct.START_SELECTION, ComposeAct.CUT, ComposeAct.COPY, ComposeAct.PASTE -> FieldSelection.label(a.textAct()!!)
        },
    )

    private fun show(m: Menu<ComposeAct>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        when (a) {
            ComposeAct.SEND -> send()
            ComposeAct.SAVE_DRAFT -> save(then = { finish() })
            ComposeAct.CLOSE -> leave()
            ComposeAct.SWITCH -> SwitchApplication.start(this)
            ComposeAct.START_SELECTION, ComposeAct.CUT, ComposeAct.COPY, ComposeAct.PASTE -> (currentFocus as? EditText)?.let { selection.perform(it, a.textAct()!!) }
        }
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Kept, then sent by apps/hub; sent, the draft goes; refused, it stays and the screen says why. */
    private fun send() {
        if (sending || locked()) return
        val d = draft
        if (!d.sendable) return status(this, getString(R.string.mail_bad_address))
        sending = true
        sendButton.ready = false
        val signature = accounts.firstOrNull { it.id == d.account }?.signature
        val g = graph
        g.ioThread.post {
            val kept = g.drafts.save(d)
            val result = g.mail.send(kept, signature)
            if (result == MailResult.OK) g.drafts.delete(kept.id!!)
            runOnUiThread {
                sending = false
                if (result == MailResult.OK) return@runOnUiThread finish()
                // The next save or send writes the same draft, not a second one.
                draft = draft.copy(id = kept.id)
                original = original.copy(id = kept.id)
                sendButton.ready = draft.sendable
                val account = accounts.firstOrNull { it.id == d.account }?.address.orEmpty()
                status(this, if (result == MailResult.AUTH) getString(R.string.mail_not_sent_auth, account) else getString(R.string.mail_not_sent))
            }
        }
    }

    private fun save(then: () -> Unit) {
        val g = graph
        val d = draft
        g.ioThread.post {
            val kept = g.drafts.save(d)
            runOnUiThread {
                draft = draft.copy(id = kept.id)
                status(this, getString(R.string.mail_draft_saved))
                sendButton.postDelayed(then, Durations.STATUS_MS)
            }
        }
    }

    /** Escape or Close: "Changes made!" with Save focused, Discard, Cancel (sim-47, C). */
    private fun leave() {
        if (draft.sameAs(original) || draft.empty) return finish()
        Bold7Dialog(this, getString(R.string.changes_made), listOf(getString(R.string.button_save), getString(R.string.button_discard), getString(R.string.button_cancel)), DialogIcon.QUESTION, onButton = {
            when (it) {
                0 -> save(then = { finish() })
                1 -> finish()
            }
        }).show()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return if (menu != null) true else super.dispatchKeyEvent(e)
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            val k = when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> if (click) MenuKey.CLICK else return true
            }
            when (val r = m.key(k)) {
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> show(null)
            }
            return true
        }
        // Start Selection: the trackpad extends the selection until Cut, Copy or Escape.
        if (selection.key(e)) return true
        // The proposals under To or Cc: down into them, up back to the field, the click takes one.
        if (proposals.isNotEmpty()) {
            when {
                code == KeyEvent.KEYCODE_DPAD_DOWN -> return true.also { movePick(pick + 1) }
                code == KeyEvent.KEYCODE_DPAD_UP && pick >= 0 -> return true.also { movePick(pick - 1) }
                click -> return true.also { take(pick.coerceAtLeast(0)) }
            }
        }
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> leave()
            KeyEvent.KEYCODE_MENU -> {
                val field = currentFocus as? EditText
                show(composeMenu(draft.sendable && !sending, if (field != null) selection.acts(field) else emptyList()))
            }
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    private fun movePick(to: Int) {
        pick = to.coerceIn(-1, proposals.lastIndex)
        proposalAdapter.notifyDataSetChanged()
        if (pick >= 0) proposalList.setSelection(pick)
    }

    private inner class ProposalAdapter : BaseAdapter() {
        override fun getCount() = proposals.size
        override fun getItem(position: Int) = proposals[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? ListItemView) ?: ListItemView(this@ComposeMailActivity)).apply {
                val a = proposals[position]
                bind(a.shown, a.email, lit = position == pick)
            }
    }
}

/** A white box of a mail's screens, its 1 px border #C6C6C6 (sim-46, gsm 030, C). */
internal class MailBoxLayout(context: Context) : LinearLayout(context) {
    private val border = fillPaint(MailBorder)
    private val white = fillPaint(ListColors.Background)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setPadding(1, 1, 1, 1)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, white)
    }
}

/** sim-46's send button: border_button with send_email.png (A), its image faint until the mail can leave (C). */
internal class SendButton(context: Context) : View(context) {
    private val normal = themeImage(context, "ui_button")
    private val lit = themeImage(context, "ui_button_focus")
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var glyph: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }
    var ready = false
        set(value) {
            field = value
            invalidate()
        }

    init {
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        contentDescription = context.getString(R.string.mail_send)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        (if (isFocused) lit else normal).apply { setBounds(-3, -3, width + 3, height + 3) }.draw(canvas)
        val g = glyph ?: return
        // Faint by alpha, by eye against sim-46; the theme has no disabled send_email.
        paint.alpha = if (ready) 255 else 90
        canvas.drawBitmap(g, (width - g.width) / 2f, (height - g.height) / 2f, paint)
    }
}
