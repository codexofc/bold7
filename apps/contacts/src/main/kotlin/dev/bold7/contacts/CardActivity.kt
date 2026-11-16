package dev.bold7.contacts

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.ContactsContract
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.bold7.design.ListColors
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.TitleBar

/**
 * A contact's card (gsm 015, C): its name as the title, then [CardView]. The trackpad walks the
 * numbers and addresses; Send calls the focused number; the click and the trackpad held open the
 * short menu, the Menu key the full one (Call, Text, Email, Copy, Edit, Delete); Escape goes back
 * to the list. Read again when the provider changes; closed when the contact is gone.
 */
class CardActivity : Activity() {
    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var titleBar: TitleBar
    private lateinit var scroll: ScrollView
    private lateinit var cardView: CardView
    private lateinit var menuView: MenuView<CardAct>

    private var contactId = -1L
    private var screen: CardScreen? = null
    private var menu: Menu<CardAct>? = null
    private var held = false
    /** The full menu a submenu was opened from. */
    private var parent: Menu<CardAct>? = null
    private var picture: Bitmap? = null
    private val glyphs = arrayOfNulls<Bitmap>(CardAct.entries.size)

    private val titles by lazy {
        mapOf(Section.PHONES to getString(R.string.section_phones), Section.EMAILS to getString(R.string.section_emails), Section.ADDRESSES to getString(R.string.section_addresses))
    }

    private val observer by lazy {
        object : ContentObserver(graph.io) {
            override fun onChange(selfChange: Boolean) = load()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactId = intent.getLongExtra(EXTRA_ID, -1)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        titleBar = TitleBar(this)
        cardView = CardView(this, onTouched = { d ->
            val s = screen ?: return@CardView
            if (s.focused == d) show(s.shortMenu()) else render(s.moved(d))
        }, onEdit = { startActivity(Intent(this, EditActivity::class.java).putExtra(EXTRA_ID, contactId)) })
        scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            addView(cardView)
        }
        menuView = MenuView(this, glyph = { glyphs[it.ordinal] }, label = ::label, onEntry = ::chosen, onExpand = { menu?.let { show(it.expand()) } }, onClose = { show(null) })
        // No status bar of Android's: the 9900 shows its title bar alone (sim-178).
        window.hideStatusBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ListColors.GroupBackground)
            addView(LinearLayout(this@CardActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleBar, LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        graph.io.post {
            val images = CardAct.entries.map { a -> glyphOf(a)?.let { BitmapFactory.decodeResource(resources, it, BitmapFactory.Options().apply { inScaled = false }) } }
            runOnUiThread { images.forEachIndexed { i, b -> glyphs[i] = b } }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.io.post { contentResolver.registerContentObserver(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId), true, observer) }
        load()
    }

    override fun onStop() {
        graph.io.post { contentResolver.unregisterContentObserver(observer) }
        super.onStop()
    }

    private fun load() {
        val g = graph
        g.io.post {
            val card = g.store.card(contactId)
            val image = card?.photo?.let { g.store.thumbnail(it) }
            runOnUiThread {
                if (card == null) return@runOnUiThread finish()
                picture = image
                titleBar.bind(card.name)
                render(screen?.loaded(card) ?: CardScreen(card))
            }
        }
    }

    private fun render(s: CardScreen) {
        screen = s
        cardView.bind(s, titles, getString(R.string.section_groups), picture)
        cardView.post { cardView.focusedRect()?.let { cardView.requestRectangleOnScreen(it, false) } }
    }

    // Menus

    private fun label(a: CardAct): String {
        val s = screen
        val d = s?.focused
        return when (a) {
            // With the contact's name (sim-195, C).
            CardAct.CALL -> getString(R.string.act_call, s?.card?.name.orEmpty())
            CardAct.TEXT -> getString(R.string.act_text, s?.card?.name.orEmpty())
            CardAct.EMAIL -> getString(R.string.act_email, s?.card?.name.orEmpty())
            CardAct.COPY -> getString(R.string.act_copy)
            CardAct.EDIT -> getString(R.string.act_edit)
            CardAct.DELETE -> getString(R.string.act_delete)
            CardAct.SWITCH -> getString(R.string.act_switch_application)
            CardAct.SEND -> getString(R.string.act_send_card)
            CardAct.SEND_EMAIL -> getString(R.string.act_send_email)
            CardAct.SEND_TEXT -> getString(R.string.act_send_text)
            CardAct.HELP -> getString(R.string.act_help)
            CardAct.CLOSE -> getString(R.string.act_close)
        }
    }

    private fun show(m: Menu<CardAct>?) {
        if (m == null) parent = null
        menu = m
        menuView.bind(m)
    }

    /** Back over a submenu: the menu under it; else the menu closes. */
    private fun closeMenu() = show(parent)

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        if (a == CardAct.SEND) {
            val back = menu
            show(sendCardActions())
            parent = back
            return
        }
        show(null)
        val s = screen ?: return
        val d = s.focused
        when (a) {
            CardAct.CALL -> s.number?.let { callNumber(it.value) }
            CardAct.TEXT -> s.number?.let { textNumber(it.value, s.card.name) }
            CardAct.EMAIL -> s.email?.let { emailAddress(it.value, s.card.name) }
            // Android 13+ already confirms the copy in its own clipboard preview.
            CardAct.COPY -> d?.let { copyText(it.value) }
            CardAct.EDIT -> startActivity(Intent(this, EditActivity::class.java).putExtra(EXTRA_ID, contactId))
            CardAct.DELETE -> deleteContact(contactId, s.card.name) { finish() }
            CardAct.SWITCH -> SwitchApplication.start(this)
            CardAct.SEND -> Unit // its submenu, opened above
            CardAct.SEND_EMAIL -> sendCard(contactId, byEmail = true)
            CardAct.SEND_TEXT -> sendCard(contactId, byEmail = false)
            // Help has no source for its text: it only closes the menu, as in the other apps.
            CardAct.HELP -> Unit
            CardAct.CLOSE -> finish()
        }
    }

    // Keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val s = screen
        if (e.action == KeyEvent.ACTION_UP) {
            if (!click) return super.dispatchKeyEvent(e)
            if (held) held = false else if (menu == null && s != null) show(s.shortMenu())
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            // The click's release belongs to the menu: it must not open the short menu again.
            if (click) held = true
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
                MenuResult.Close -> closeMenu()
            }
            return true
        }
        if (s == null) return super.dispatchKeyEvent(e)
        when {
            code == KeyEvent.KEYCODE_DPAD_UP -> render(s.step(down = false))
            code == KeyEvent.KEYCODE_DPAD_DOWN -> render(s.step(down = true))
            code == KeyEvent.KEYCODE_MENU -> show(s.menu())
            code == KeyEvent.KEYCODE_CALL -> if (e.repeatCount == 0) s.number?.let { callNumber(it.value) }
            click -> if (e.isLongPress || e.repeatCount == 1) {
                held = true
                show(s.shortMenu())
            }
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }
}

/** The short menu's image of each action of the card (sm_*.png of the theme, A; the match D). */
fun glyphOf(a: CardAct): Int? = when (a) {
    CardAct.CALL -> R.drawable.cnt_sm_call
    CardAct.TEXT -> R.drawable.cnt_sm_sms
    CardAct.EMAIL -> R.drawable.cnt_sm_compose
    CardAct.COPY -> R.drawable.cnt_sm_copy
    CardAct.EDIT -> R.drawable.cnt_sm_edit
    CardAct.DELETE -> R.drawable.cnt_sm_delete
    CardAct.SWITCH, CardAct.SEND, CardAct.SEND_EMAIL, CardAct.SEND_TEXT, CardAct.HELP, CardAct.CLOSE -> null // in the full menu only
}
