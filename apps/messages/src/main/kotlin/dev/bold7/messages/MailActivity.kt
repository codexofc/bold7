package dev.bold7.messages

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.bold7.design.Durations
import dev.bold7.design.ListColors
import dev.bold7.design.Typography
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.ItemKey
import dev.bold7.showcase.showcaseOn
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.LetterHold
import dev.bold7.ui.MenuView
import dev.bold7.ui.SpeedDialKey
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.withSwitchApplication
import dev.bold7.ui.fillPaint
import dev.bold7.ui.slate
import dev.bold7.ui.status
import dev.bold7.ui.textPaint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The grey around the boxes of a mail's screens (gsm 030, sim-46, C: #DDDDDD), their border (#C6C6C6). */
internal val MailGrey = 0xFFDDDDDD.toInt()
internal val MailBorder = 0xFFC6C6C6.toInt()

/** "16 Sep 2011 13:36" (gsm 030, C), or in 12 h by the phone's setting. */
internal fun mailDate(context: Context): (Long) -> String {
    val f = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "d MMM yyyy HH:mm" else "d MMM yyyy h:mm a", context.resources.configuration.locales[0])
    return { f.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
}

/**
 * A mail opened in Messages (gsm 030, gsm 032, C): the header box, "From:" and the
 * sender in dark grey, the subject in bold, the date; then the text, plain (an HTML mail as text:
 * no WebView, nothing reaches the network, nothing runs). Opening it marks it read, in the Hub and
 * on its server. The trackpad scrolls; R replies, L replies to all, F forwards (guide 7.1 p. 51,
 * B); Space a screen, T and B top and bottom; Menu or the click held: Reply (focused, gsm 032),
 * Forward, Reply To All, then Mark Unread, Delete, Close. Never over the lock screen.
 */
class MailActivity : Activity() {
    enum class Act { REPLY, FORWARD, REPLY_ALL, MARK_UNREAD, DELETE, CLOSE, SWITCH }

    companion object {
        private const val EXTRA_ACCOUNT = "account"
        private const val EXTRA_KEY = "key"

        /** The header box: its border at y 2 and 97, 4 px from the sides (gsm 030, C). */
        const val SIDE = 4
        const val HEADER_TOP = 2
        const val HEADER_HEIGHT = 96
        /** From, subject and date: baselines 32, 63 and 88, text at x 10 (gsm 030, C); the date in 20 px (its digits 14 px high, C). */
        const val TEXT_X = 10
        const val FROM_BASELINE = 32
        const val SUBJECT_BASELINE = 63
        const val DATE_BASELINE = 88
        const val DATE_SIZE = 20
        /** The text at x 14, its first baseline 41 px under the header, 35 px a line (gsm 030, C). */
        const val BODY_X = 14
        const val BODY_LINE = 35
        const val BODY_TOP = 14

        fun intent(context: Context, key: ItemKey): Intent =
            Intent(context, MailActivity::class.java).putExtra(EXTRA_ACCOUNT, key.accountId).putExtra(EXTRA_KEY, key.sourceKey)
    }

    private val graph get() = (application as MessagesApp).graph
    private lateinit var key: ItemKey
    private var opened: Opened? = null
    private var menu: Menu<Act>? = null
    private var held = false
    private val glyphs = HashMap<Act, Bitmap?>()
    private lateinit var header: HeaderView
    private lateinit var scroll: ScrollView
    private lateinit var body: TextView
    private lateinit var menuView: MenuView<Act>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = ItemKey(intent.getStringExtra(EXTRA_ACCOUNT).orEmpty(), intent.getStringExtra(EXTRA_KEY).orEmpty())
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        header = HeaderView(this)
        body = TextView(this).apply {
            typeface = slate(Typography.REGULAR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
            setTextColor(ListColors.Title)
            setLineHeight(BODY_LINE)
            includeFontPadding = false
            setPadding(BODY_X - SIDE - 1, BODY_TOP, BODY_X, BODY_TOP)
            setBackgroundColor(ListColors.Background)
            text = getString(R.string.mail_loading)
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            isFocusable = false
            setBackgroundColor(ListColors.Background)
            addView(body, ViewGroup.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        menuView = MenuView(this, glyph = { glyphs[it] }, label = ::label, onEntry = ::chosen, onExpand = {}, onClose = { show(null) })
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(MailGrey)
            addView(LinearLayout(this@MailActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(header, LinearLayout.LayoutParams(match, HEADER_TOP + HEADER_HEIGHT + 1))
                addView(scroll, LinearLayout.LayoutParams(match, 0, 1f).apply {
                    leftMargin = SIDE + 1
                    rightMargin = SIDE + 1
                })
            })
            addView(menuView)
        })
        val g = graph
        g.ioThread.post {
            val images = mapOf(
                Act.REPLY to R.drawable.msg_sm_reply, Act.FORWARD to R.drawable.msg_sm_forward, Act.REPLY_ALL to R.drawable.msg_sm_reply_all,
                Act.MARK_UNREAD to R.drawable.msg_sm_mark_unread, Act.DELETE to R.drawable.msg_sm_delete,
            ).mapValues { BitmapFactory.decodeResource(resources, it.value) }
            // A showcase email is made up (ShowcaseInbox): no account is asked, nothing is written.
            val showcase = isShowcase(key)
            val m = if (showcase) showcaseMail(key, System.currentTimeMillis()) else if (showcaseOn(this)) null else g.mail.open(key).first
            // Opened is read (guide 7.1 p. 94-95: Mark Unread undoes it), in the Hub and on the server.
            if (m != null && !showcase && !locked()) g.mail.write(HubContract.METHOD_MARK_READ, key)
            runOnUiThread {
                glyphs.putAll(images)
                display(m)
            }
        }
    }

    private fun display(m: Opened?) {
        if (m == null) {
            body.text = ""
            status(this, getString(R.string.mail_unreadable))
            body.postDelayed({ finish() }, Durations.STATUS_MS)
            return
        }
        opened = m
        header.bind(m.from?.shown.orEmpty(), m.subject, mailDate(this)(m.date))
        body.text = m.text
        title = m.subject
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun label(a: Act) = getString(
        when (a) {
            Act.REPLY -> R.string.mail_reply
            Act.FORWARD -> R.string.mail_forward
            Act.REPLY_ALL -> R.string.mail_reply_all
            Act.MARK_UNREAD -> R.string.act_mark_unread
            Act.DELETE -> R.string.act_delete
            Act.CLOSE -> R.string.mail_close
            Act.SWITCH -> R.string.act_switch_application
        },
    )

    private fun entries(full: Boolean): List<MenuEntry<Act>> = listOf(
        MenuEntry(Act.REPLY), MenuEntry(Act.FORWARD), MenuEntry(Act.REPLY_ALL),
        MenuEntry(Act.MARK_UNREAD, separatorAbove = full), MenuEntry(Act.DELETE),
        MenuEntry(Act.CLOSE, separatorAbove = full),
    )

    private fun show(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        perform(a)
    }

    private fun perform(a: Act) {
        if (locked()) return
        // A showcase email only closes: its replies and writes would reach a real account.
        if (isShowcase(key) && a != Act.CLOSE && a != Act.SWITCH) return
        val m = opened
        val date = mailDate(this)
        when (a) {
            Act.REPLY, Act.REPLY_ALL -> m?.let { compose(reply(it, key.accountId, all = a == Act.REPLY_ALL, date)) }
            Act.FORWARD -> m?.let { compose(forward(it, key.accountId, date)) }
            Act.MARK_UNREAD -> {
                graph.ioThread.post { graph.mail.write(HubContract.METHOD_MARK_UNREAD, key) }
                finish()
            }
            // Confirm Delete, on by default (guide p. 89, B), as the inbox asks it (Messages Options).
            Act.DELETE -> {
                val delete = {
                    if (!locked()) {
                        graph.ioThread.post { graph.mail.write(HubContract.METHOD_DELETE, key) }
                        finish()
                    }
                }
                if (graph.store.options.confirmDelete) {
                    Bold7Dialog(this, getString(R.string.confirm_delete), listOf(getString(R.string.button_delete), getString(R.string.button_cancel)), DialogIcon.QUESTION, onButton = { if (it == 0) delete() }).show()
                } else {
                    delete()
                }
            }
            Act.CLOSE -> finish()
            Act.SWITCH -> SwitchApplication.start(this)
        }
    }

    private fun compose(d: MailDraft) {
        startActivity(Intent(this, ComposeMailActivity::class.java).putExtra(ComposeMailActivity.EXTRA_PREFILL, d.encoded()))
    }

    // The letters of the message wait for their up: held, one calls its speed dial (guide 7.1 p. 65).
    private val letters = LetterHold()

    private fun letterOut(out: LetterHold.Out) = when (out) {
        is LetterHold.Out.Tap -> when (out.letter.lowercaseChar()) {
            'r' -> perform(Act.REPLY)
            'l' -> perform(Act.REPLY_ALL)
            'f' -> perform(Act.FORWARD)
            't' -> scroll.smoothScrollTo(0, 0)
            'b' -> scroll.smoothScrollTo(0, body.height)
            else -> Unit
        }
        is LetterHold.Out.Hold -> SpeedDialKey.call(this, out.letter)
    }

    override fun onPause() {
        letters.clear()
        super.onPause()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val letter = menu == null && !e.isAltPressed && e.displayLabel.lowercaseChar() in 'a'..'z'
        if (!letter) letters.flush()?.let(::letterOut)
        if (e.action == KeyEvent.ACTION_UP) {
            if (click && held) held = false
            letters.up(code)?.let(::letterOut)
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            val k = when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
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
        val page = (scroll.height - BODY_LINE).coerceAtLeast(BODY_LINE)
        when {
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE -> finish()
            code == KeyEvent.KEYCODE_MENU -> show(Menu(entries(full = true), highlighted = 0).withSwitchApplication(Act.SWITCH))
            click -> if (e.isLongPress || e.repeatCount == 1) {
                held = true
                show(Menu(entries(full = false), line = 0, highlighted = 0))
            }
            code == KeyEvent.KEYCODE_DPAD_DOWN -> scroll.smoothScrollBy(0, BODY_LINE)
            code == KeyEvent.KEYCODE_DPAD_UP -> scroll.smoothScrollBy(0, -BODY_LINE)
            code == KeyEvent.KEYCODE_SPACE -> scroll.smoothScrollBy(0, if (e.isShiftPressed) -page else page)
            letter -> letters.down(code, e.displayLabel, e.repeatCount).forEach(::letterOut)
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    /** The header box of gsm 030: drawn, one line each, cut with an ellipsis (D: the 9900 wraps a long subject). */
    private class HeaderView(context: Context) : View(context) {
        // The sender's dark grey, #3D3D3D to #404040 on gsm 030 (C).
        private val from = TextPaint(textPaint(Typography.TEXT, Typography.REGULAR, 0xFF404040.toInt()))
        private val subject = TextPaint(textPaint(Typography.TEXT, Typography.BOLD, ListColors.Title))
        private val date = textPaint(DATE_SIZE, Typography.REGULAR, ListColors.Title)
        private val white = fillPaint(ListColors.Background)
        private val border = fillPaint(MailBorder)
        private var lines = Triple("", "", "")
        private var fromLabel = ""

        fun bind(sender: String, subjectText: String, dateText: String) {
            fromLabel = context.getString(R.string.mail_from, sender)
            lines = Triple(fromLabel, subjectText, dateText)
            contentDescription = "$fromLabel. $subjectText. $dateText"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val right = w - SIDE
            canvas.drawRect(SIDE.toFloat(), HEADER_TOP.toFloat(), right, (HEADER_TOP + HEADER_HEIGHT + 1).toFloat(), border)
            canvas.drawRect(SIDE + 1f, HEADER_TOP + 1f, right - 1, (HEADER_TOP + HEADER_HEIGHT).toFloat(), white)
            val room = right - TEXT_X - 6
            fun fit(s: String, p: TextPaint) = TextUtils.ellipsize(s, p, room, TextUtils.TruncateAt.END).toString()
            canvas.drawText(fit(lines.first, from), TEXT_X.toFloat(), FROM_BASELINE.toFloat(), from)
            canvas.drawText(fit(lines.second, subject), TEXT_X.toFloat(), SUBJECT_BASELINE.toFloat(), subject)
            canvas.drawText(lines.third, TEXT_X.toFloat(), DATE_BASELINE.toFloat(), date)
        }
    }
}
