package dev.bold7.memopad

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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
import dev.bold7.design.ListColors
import dev.bold7.design.Typography
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.LineItemView
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.SearchField
import dev.bold7.ui.TitleBar
import dev.bold7.ui.bb10List
import dev.bold7.ui.slate
import dev.bold7.ui.status

/**
 * The memo list of BBOS 7.1 (docs/BBOS7-VISUEL.md §32, §34.2): the title "MemoPad", the Search
 * field, "New Memo:" then the memos in alphabetical order, one 57 px line each (sim-254, sim-259,
 * C), the first memo focused. Typing filters (the guide's lists, B), Escape clears what is typed
 * then leaves; the click edits the memo (sim-266, C; New Memo: the form), the Menu key and the
 * trackpad held open the full and the short menu. Renders [MemoList]; the database is read off the
 * main thread.
 */
class MemoListActivity : Activity() {
    private lateinit var field: SearchField
    private lateinit var list: ListView
    private lateinit var none: TextView
    private lateinit var menuView: MenuView<ListAct>

    private var state = MemoList()
    private var menu: Menu<ListAct>? = null
    private var options = MemoOptions()
    private var held = false
    private val glyphs = arrayOfNulls<Bitmap>(ListAct.entries.size)
    private val adapter = Rows()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        // Typed through dispatchKeyEvent: the trackpad's focus stays on the list (as Contacts').
        field = SearchField(this, getString(R.string.search)).apply { isFocusable = false }
        list = bb10List(ListView(this), 0).apply {
            adapter = this@MemoListActivity.adapter
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
        }
        none = TextView(this).apply {
            typeface = slate(Typography.REGULAR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
            setTextColor(ListColors.Title)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 3, 0, 0)
            text = getString(R.string.no_memos)
            visibility = View.GONE
        }
        menuView = MenuView(this, glyph = { glyphs[it.ordinal] }, label = ::label, onEntry = ::chosen, onExpand = { menu?.let { show(it.expand()) } }, onClose = { show(null) })
        // No status bar of Android's: the 9900 shows its title bar alone (sim-254).
        window.hideStatusBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ListColors.Background)
            addView(LinearLayout(this@MemoListActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TitleBar(this@MemoListActivity, getString(R.string.title)), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(field, LinearLayout.LayoutParams(match, SearchField.HEIGHT))
                addView(FrameLayout(this@MemoListActivity).apply {
                    addView(list, FrameLayout.LayoutParams(match, match))
                    // Under New Memo, the list's only row then.
                    addView(none, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = LineItemView.HEIGHT })
                }, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        graph.io.post {
            val images = ListAct.entries.map { a -> glyphOf(a)?.let { BitmapFactory.decodeResource(resources, it, BitmapFactory.Options().apply { inScaled = false }) } }
            runOnUiThread { images.forEachIndexed { i, b -> glyphs[i] = b } }
        }
    }

    override fun onStart() {
        super.onStart()
        load()
    }


    /** The database read again off the main thread (the screen came back from the form). */
    private fun load() {
        val g = graph
        g.io.post {
            val memos = g.memos.all()
            val o = g.options
            runOnUiThread {
                options = o
                update(ListEvent.Loaded(memos))
            }
        }
    }

    private fun update(e: ListEvent) = set(state.reduce(e))

    private fun set(next: MemoList) {
        val old = state
        state = next
        if (next.typed != old.typed) field.setText(next.field)
        if (next.rows != old.rows || next.typed != old.typed) adapter.notifyDataSetChanged() else if (next.focus != old.focus) list.invalidateViews()
        none.visibility = if (next.empty) View.VISIBLE else View.GONE
        if (next.focus < list.firstVisiblePosition || next.focus > list.lastVisiblePosition) list.setSelection(next.focus)
    }

    // Actions

    private fun open() {
        when (state.focused) {
            NewMemo -> act(ListAct.NEW)
            is Entry -> act(ListAct.EDIT)
            null -> Unit
        }
    }

    private fun act(a: ListAct) {
        val m = (state.focused as? Entry)?.memo
        when (a) {
            // What is typed becomes the new memo's title (D, as Contacts' new contact).
            ListAct.NEW -> startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_TITLE, state.field.trim()))
            ListAct.EDIT -> m?.let { startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_ID, it.id)) }
            ListAct.DELETE -> m?.let(::delete)
            ListAct.OPTIONS -> startActivity(Intent(this, OptionsActivity::class.java))
            ListAct.CLOSE -> finish()
            ListAct.SWITCH -> SwitchApplication.start(this)
        }
    }

    /** Delete: asked first, Cancel focused (sim-199, C, as Contacts'), unless Options' Confirm Delete is off (sim-263, C). */
    private fun delete(m: Memo) {
        val g = graph
        val remove = {
            g.io.post {
                val gone = g.memos.delete(m.id)
                runOnUiThread { if (gone) load() else status(this, getString(R.string.not_deleted)) }
            }
        }
        if (!options.confirmDelete) {
            remove()
            return
        }
        val name = m.shownTitle.ifEmpty { getString(R.string.no_title) }
        Bold7Dialog(this, getString(R.string.delete_question, name), listOf(getString(R.string.act_delete), getString(R.string.cancel)), DialogIcon.QUESTION, focused = 1, onButton = { b ->
            if (b == 0) remove()
        }).show()
    }

    // Menus

    private fun label(a: ListAct) = getString(when (a) {
        ListAct.NEW -> R.string.act_new
        ListAct.EDIT -> R.string.act_edit
        ListAct.DELETE -> R.string.act_delete
        ListAct.OPTIONS -> R.string.act_options
        ListAct.CLOSE -> R.string.act_close
        ListAct.SWITCH -> R.string.act_switch_application
    })

    private fun show(m: Menu<ListAct>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        act(a)
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
            code == KeyEvent.KEYCODE_DPAD_UP -> update(ListEvent.Step(down = false))
            code == KeyEvent.KEYCODE_DPAD_DOWN -> update(ListEvent.Step(down = true))
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE -> set(state.back() ?: return super.dispatchKeyEvent(e))
            code == KeyEvent.KEYCODE_DEL -> update(ListEvent.Erased)
            code == KeyEvent.KEYCODE_MENU -> show(state.menu())
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
            NewMemo -> Long.MIN_VALUE
            is Entry -> r.memo.id
        }
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView as? LineItemView ?: LineItemView(this@MemoListActivity)
            val lit = position == state.focus
            when (val r = state.rows[position]) {
                NewMemo -> v.bind(getString(R.string.new_memo), lit)
                is Entry -> v.bind(r.memo.shownTitle.ifEmpty { getString(R.string.no_title) }, lit)
            }
            return v
        }
    }
}

/** The short menu's image of each action (sm_*.png of the theme, A; the match D, Edit's pencil C: sim-265); the others have none. */
fun glyphOf(a: ListAct): Int? = when (a) {
    ListAct.NEW -> R.drawable.mem_sm_new
    ListAct.EDIT -> R.drawable.mem_sm_edit
    ListAct.DELETE -> R.drawable.mem_sm_delete
    ListAct.SWITCH -> null // in the full menu only
    else -> null
}
