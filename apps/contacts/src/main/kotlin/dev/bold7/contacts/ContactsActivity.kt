package dev.bold7.contacts

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import dev.bold7.design.ListColors
import dev.bold7.design.Typography
import dev.bold7.platform.hideStatusBar
import dev.bold7.showcase.isAvatar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.SearchField
import dev.bold7.ui.TitleBar
import dev.bold7.ui.bb10List
import dev.bold7.ui.slate
import dev.bold7.ui.themeBitmap

/**
 * The contact list of BBOS 7.1 (gsm 013, C; docs/BBOS7-VISUEL.md §30): the title "Contacts", the
 * Search field, New Contact then the contacts. Typing filters (guide p. 192, B), Escape clears what
 * is typed then leaves; the click views the contact (New Contact: the form), Send calls it, the
 * Menu key and the trackpad held open the full and the short menu. Renders [ContactList].
 *
 * As Select Contact ([SelectContactActivity], ACTION_PICK, for Bold7's apps only: a signature
 * permission), the same list titled "Select Contact": the click returns the contact, or with the
 * phones' type (ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE) one of its numbers, its data
 * row's URI with a read grant, as Android's pickers do.
 *
 * `su -c 'am start -n dev.bold7.contacts/.ContactsActivity --ei rows 500'`: made-up contacts,
 * nothing read (tools/perf.sh, APP mode).
 */
open class ContactsActivity : Activity() {
    companion object {
        private const val NEW_CONTACT = 1

        private const val EXTRA_ROWS = "rows"
        private const val DEMO_MAX = 2000
    }

    private lateinit var field: SearchField
    private lateinit var list: ListView
    private lateinit var none: TextView
    private lateinit var menuView: MenuView<ListAct>

    private var state = ContactList()
    private var menu: Menu<ListAct>? = null
    private var held = false
    /** The full menu a submenu was opened from. */
    private var parent: Menu<ListAct>? = null
    private var demo = false
    private var kind = PickKind.CONTACT
    private var newPicture: Bitmap? = null
    private var defaultPicture: Bitmap? = null
    private val glyphs = arrayOfNulls<Bitmap>(ListAct.entries.size)
    private val adapter = Rows()
    /** The pictures asked of the io thread and not back yet. */
    private val asked = HashSet<String>()

    private val observer by lazy {
        object : ContentObserver(graph.io) {
            override fun onChange(selfChange: Boolean) = load()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pick = Request.parse(intent.action, intent.dataString, intent.type, emptyMap()) as? Request.Pick
        val picking = pick != null
        kind = pick?.kind ?: PickKind.CONTACT
        state = ContactList(picking = picking)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        field = SearchField(this, getString(R.string.search)).apply {
            // Typed through dispatchKeyEvent: the trackpad's focus stays on the list (as the Phone's).
            isFocusable = false
        }
        list = bb10List(ListView(this), 0).apply {
            adapter = this@ContactsActivity.adapter
            isFocusable = false
            isHapticFeedbackEnabled = false
            setOnItemClickListener { _, _, position, _ ->
                update(ListEvent.Moved(position))
                open()
            }
            setOnItemLongClickListener { _, _, position, _ ->
                update(ListEvent.Moved(position))
                show(state.shortMenu())
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = askPictures(first, visible)
            })
        }
        // "* No Contacts *" under New Contact when nothing matches (sim-190, C).
        none = TextView(this).apply {
            typeface = slate(Typography.REGULAR)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
            setTextColor(ListColors.Title)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 3, 0, 0)
            text = getString(R.string.no_contacts)
            visibility = View.GONE
        }
        menuView = MenuView(this, glyph = { glyphs[it.ordinal] }, label = ::label, onEntry = ::chosen, onExpand = { menu?.let { show(it.expand()) } }, onClose = { show(null) })
        // No status bar of Android's: the 9900 shows its title bar alone (sim-178).
        window.hideStatusBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ListColors.Background)
            addView(LinearLayout(this@ContactsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TitleBar(this@ContactsActivity, getString(if (picking) R.string.select_title else R.string.title)), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(field, LinearLayout.LayoutParams(match, SearchField.HEIGHT))
                addView(FrameLayout(this@ContactsActivity).apply {
                    addView(list, FrameLayout.LayoutParams(match, match))
                    // Under New Contact, the list's only row then.
                    addView(none, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = PersonRowView.HEIGHT })
                }, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        val rows = intent.getIntExtra(EXTRA_ROWS, 0)
        demo = rows > 0
        if (demo) set(state.reduce(ListEvent.Loaded(List(rows.coerceAtMost(DEMO_MAX)) { i -> demoPerson(i) })))
        graph.io.post {
            // New Contact's: 102 px in the firmware, 67 on sim-178 (D: scaled down).
            val newImage = decode("cnt_new")?.let { Bitmap.createScaledBitmap(it, PersonRowView.PICTURE, PersonRowView.PICTURE, true) }
            val frame = decode("cnt_picture")
            val images = ListAct.entries.map { glyphOf(it)?.let(::decode) }
            runOnUiThread {
                newPicture = newImage
                defaultPicture = frame
                images.forEachIndexed { i, b -> glyphs[i] = b }
                list.invalidateViews()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (demo) return
        graph.io.post { contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer) }
        load()
    }

    override fun onStop() {
        if (!demo) graph.io.post { contentResolver.unregisterContentObserver(observer) }
        super.onStop()
    }

    private fun decode(name: String): Bitmap = themeBitmap(applicationContext, name)

    private fun demoPerson(i: Int): Person {
        val first = listOf("Alice", "Bruno", "Chloe", "David", "Ellen", "Frank", "Gail", "Hugo")[i % 8]
        return Person(-1L - i, "$first Demo ${i + 1}", phone = "5550100", email = null)
    }

    /** The provider read again off the main thread (it changed, or the screen came back). */
    private fun load() {
        if (demo) return
        val g = graph
        g.io.post {
            val people = g.store.people()
            runOnUiThread { update(ListEvent.Loaded(people)) }
        }
    }

    /** The visible rows' pictures, decoded off the main thread once each, kept by the graph's cache. */
    private fun askPictures(first: Int, count: Int) {
        val g = graph
        for (i in first until (first + count).coerceAtMost(state.rows.size)) {
            val uri = ((state.rows[i] as? Entry)?.person?.photo) ?: continue
            if (g.pictures.get(uri) != null || !asked.add(uri)) continue
            g.io.post {
                val b = g.store.thumbnail(uri)
                runOnUiThread {
                    asked.remove(uri)
                    if (b != null) {
                        g.pictures.put(uri, b)
                        list.invalidateViews()
                    }
                }
            }
        }
    }

    // State

    private fun update(e: ListEvent) = set(state.reduce(e))

    // The contact just saved takes the focus, now or once the list has read it (sim-193, C).
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == NEW_CONTACT && resultCode == RESULT_OK && uri != null) update(ListEvent.Created(ContentUris.parseId(uri)))
    }

    private fun set(next: ContactList) {
        val old = state
        state = next
        if (next.typed != old.typed) {
            field.setText(next.field)
            // New Contact's note names what is typed (sim-189, C).
            adapter.notifyDataSetChanged()
        }
        if (next.rows != old.rows) adapter.notifyDataSetChanged() else if (next.focus != old.focus) list.invalidateViews()
        none.visibility = if (next.noMatch) View.VISIBLE else View.GONE
        keepInSight(next.focus)
    }

    /**
     * The focused row stays on the screen without losing New Contact: the first contact's focus
     * leaves New Contact above it (sim-178, C), where setSelection would put the contact at the top.
     */
    private fun keepInSight(focus: Int) {
        val first = list.firstVisiblePosition
        val last = list.lastVisiblePosition
        when {
            focus <= 1 && first > focus -> list.setSelection(0)
            focus <= 1 -> Unit
            focus < first -> list.setSelection(focus)
            focus > last -> list.setSelectionFromTop(focus, (list.height - PersonRowView.HEIGHT).coerceAtLeast(0))
        }
    }

    // Actions

    /** The click: New Contact opens the form, a contact its card; selecting, it is chosen. */
    private fun open() {
        when (val r = state.focused) {
            NewContact -> act(ListAct.NEW)
            is Entry -> act(if (state.picking) ListAct.SELECT else ListAct.VIEW)
            null -> Unit
        }
    }

    private fun act(a: ListAct) {
        val p = (state.focused as? Entry)?.person
        if (demo && a != ListAct.NEW) return
        when (a) {
            // What is typed becomes the new contact's first name (D, after sim-189's note).
            ListAct.NEW -> startActivityForResult(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_NAME, state.field.trim()), NEW_CONTACT)
            ListAct.VIEW -> p?.let { startActivity(Intent(this, CardActivity::class.java).putExtra(CardActivity.EXTRA_ID, it.id)) }
            ListAct.EDIT -> p?.let { startActivity(Intent(this, EditActivity::class.java).putExtra(CardActivity.EXTRA_ID, it.id)) }
            ListAct.DELETE -> p?.let { deleteContact(it.id, it.name) { load() } }
            ListAct.CALL -> p?.phone?.let(::callNumber)
            ListAct.TEXT -> p?.phone?.let { textNumber(it, p.name) }
            ListAct.EMAIL -> p?.email?.let { emailAddress(it, p.name) }
            ListAct.SELECT -> p?.let(::select)
            ListAct.SWITCH -> SwitchApplication.start(this)
            ListAct.SEND -> Unit // its submenu, opened by chosen()
            ListAct.SEND_EMAIL -> p?.let { sendCard(it.id, byEmail = true) }
            ListAct.SEND_TEXT -> p?.let { sendCard(it.id, byEmail = false) }
            // Help has no source for its text: it only closes the menu, as in the other apps.
            ListAct.HELP -> dev.bold7.ui.Help.open(this, "contacts")
            ListAct.CLOSE -> finish()
        }
    }

    /** Select Contact: the contact, or one of its numbers, returned with a read grant. */
    private fun select(p: Person) {
        // A showcase person has no row of the provider: nothing is returned that could name a real one.
        if (isAvatar(p.photo)) return finish()
        if (kind == PickKind.CONTACT) return pick(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, p.id))
        val g = graph
        g.io.post {
            val want = if (kind == PickKind.EMAIL) Kind.EMAIL else Kind.PHONE
            val numbers = g.store.card(p.id)?.details?.filter { it.kind == want }.orEmpty()
            runOnUiThread {
                when (numbers.size) {
                    0 -> Unit
                    1 -> pickNumber(numbers[0])
                    // Which number: the dialog's buttons, one per number (D).
                    else -> Bold7Dialog(this, getString(if (kind == PickKind.EMAIL) R.string.select_email else R.string.select_number), numbers.map { "${it.label}: ${it.value}" }, onButton = { pickNumber(numbers[it]) }).show()
                }
            }
        }
    }

    private fun pickNumber(d: Detail) = pick(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, d.dataId))

    private fun pick(uri: android.net.Uri) {
        setResult(RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    // Menus

    private fun label(a: ListAct): String {
        val p = (state.focused as? Entry)?.person
        val name = p?.name.orEmpty()
        return when (a) {
            ListAct.NEW -> getString(R.string.act_new)
            ListAct.VIEW -> getString(R.string.act_view)
            ListAct.EDIT -> getString(R.string.act_edit)
            ListAct.DELETE -> getString(R.string.act_delete)
            ListAct.CALL -> getString(R.string.act_call, name)
            ListAct.TEXT -> getString(R.string.act_text, name)
            ListAct.EMAIL -> getString(R.string.act_email, name)
            ListAct.SELECT -> getString(R.string.act_select)
            ListAct.SWITCH -> getString(R.string.act_switch_application)
            ListAct.SEND -> getString(R.string.act_send_card)
            ListAct.SEND_EMAIL -> getString(R.string.act_send_email)
            ListAct.SEND_TEXT -> getString(R.string.act_send_text)
            ListAct.HELP -> getString(R.string.act_help)
            ListAct.CLOSE -> getString(R.string.act_close)
        }
    }

    private fun show(m: Menu<ListAct>?) {
        if (m == null) parent = null
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        if (a == ListAct.SEND) {
            // Its submenu opens at its right, the full menu staying behind (isSubmenu); Back returns to it.
            val back = menu
            show(sendCardMenu())
            parent = back
            return
        }
        show(null)
        act(a)
    }

    /** Back over a submenu: the menu under it; else the menu closes. */
    private fun closeMenu() {
        val back = parent
        show(back)
    }

    // Keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (e.action == KeyEvent.ACTION_UP) {
            if (!click) return super.dispatchKeyEvent(e)
            if (held) held = false else if (menu == null) open()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            // The click's release belongs to the menu too: it must not open the contact the menu was on (for example after Delete).
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
        when {
            code == KeyEvent.KEYCODE_DPAD_UP -> update(ListEvent.Step(down = false))
            code == KeyEvent.KEYCODE_DPAD_DOWN -> update(ListEvent.Step(down = true))
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE -> set(state.back() ?: return super.dispatchKeyEvent(e))
            code == KeyEvent.KEYCODE_DEL -> update(ListEvent.Erased)
            code == KeyEvent.KEYCODE_MENU -> show(state.menu())
            code == KeyEvent.KEYCODE_CALL -> if (e.repeatCount == 0 && !state.picking) (state.focused as? Entry)?.person?.phone?.let(::callNumber)
            click -> if (e.isLongPress || e.repeatCount == 1) {
                held = true
                show(state.shortMenu())
            }
            else -> {
                val c = e.getUnicodeChar(e.metaState)
                if (c <= 0 || Character.isISOControl(c)) return super.dispatchKeyEvent(e)
                if (e.repeatCount == 0) update(ListEvent.Typed(c.toChar()))
            }
        }
        return true
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = state.rows.size
        override fun getItem(position: Int) = state.rows[position]
        override fun getItemId(position: Int) = when (val r = state.rows[position]) {
            NewContact -> Long.MIN_VALUE
            is Entry -> r.person.id
        }
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView as? PersonRowView ?: PersonRowView(this@ContactsActivity)
            val lit = position == state.focus
            when (val r = state.rows[position]) {
                NewContact -> v.bind(
                    getString(R.string.new_contact),
                    if (state.typed.isBlank()) getString(R.string.new_contact_note) else getString(R.string.new_contact_named, state.field),
                    lit,
                    newPicture,
                )
                is Entry -> v.bind(r.person.name.ifEmpty { getString(R.string.unnamed) }, "", lit, r.person.photo?.let { graph.pictures.get(it) }, defaultPicture)
            }
            return v
        }
    }
}

/** The short menu's image of each action (sm_*.png of the theme, A; the match D). */
fun glyphOf(a: ListAct): String? = when (a) {
    ListAct.NEW -> "cnt_sm_add_contact"
    ListAct.VIEW -> "cnt_sm_view_contact"
    ListAct.EDIT -> "cnt_sm_edit"
    ListAct.DELETE -> "cnt_sm_delete"
    ListAct.CALL -> "cnt_sm_call"
    ListAct.TEXT -> "cnt_sm_sms"
    ListAct.EMAIL -> "cnt_sm_compose"
    ListAct.SELECT -> "cnt_sm_select"
    ListAct.SWITCH, ListAct.SEND, ListAct.SEND_EMAIL, ListAct.SEND_TEXT, ListAct.HELP, ListAct.CLOSE -> null // in the full menu only
}

/** Select Contact: the list in its caller's task (ContactsActivity is singleTask), for a result. */
class SelectContactActivity : ContactsActivity()

/**
 * Select Contact for the other apps: ACTION_PICK and ACTION_GET_CONTENT of a contact, a
 * number or an email address, by implicit intent, so exported with no permission (the allowed exception to the export rule of CONTRIBUTING.md).
 * It returns nothing until the user clicks a contact (or one of its numbers), and then only that
 * row's URI with a read grant.
 */
class PickContactActivity : ContactsActivity()
