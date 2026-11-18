package dev.bold7.tasks

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
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
import dev.bold7.design.Typography
import dev.bold7.design.ListColors
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.LineItemView
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SearchField
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.TitleBar
import dev.bold7.ui.bb10List
import dev.bold7.ui.slate
import dev.bold7.ui.status
import dev.bold7.ui.themeBitmap
import java.time.LocalDateTime

/**
 * The task list of BBOS 7.1 (docs/BBOS7-VISUEL.md §33, §34.3): the title "Tasks", the Search field,
 * "Add Task:" then the tasks sorted by Tasks Options' Sort By (Subject first, sim-278, C), one 57
 * px line each as MemoPad's (sim-276 to sim-280, C): the priority image (net_rim_Priority-icons9x27.png,
 * A) at the left edge, the status image (net_rim_Task_Status-icons27x27.png, A) at x 14, then the
 * title, no due date; the title red once the due time is gone (D); the first task focused. Typing
 * filters (as MemoPad's), Escape clears it then leaves. The click opens the task (Add Task: the
 * form, guide p. 206, B), the Menu key the full menu (sim-281, sim-282, C), the trackpad held the
 * short menu. Renders
 * [TaskList]; the database is read off the main thread. At each start the reminders are swept
 * (Ringer.kt): a reminder lost by a forced stop rings then.
 */
class TaskListActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var menuView: MenuView<ListAct>
    private lateinit var none: TextView
    private lateinit var field: SearchField

    private var state = TaskList()
    private var menu: Menu<ListAct>? = null
    private var held = false
    private val glyphs = arrayOfNulls<Bitmap>(ListAct.entries.size)
    /** Priority then status, one image per pair (index: priority.ordinal * statuses + status.ordinal). */
    private var images = emptyList<Bitmap>()
    private var now = LocalDateTime.now()
    private var options = TaskOptions()
    private val adapter = Rows()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        list = bb10List(ListView(this), 0).apply {
            adapter = this@TaskListActivity.adapter
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
        // Typed through dispatchKeyEvent: the trackpad's focus stays on the list (as MemoPad's).
        field = SearchField(this, getString(R.string.search)).apply { isFocusable = false }
        menuView = MenuView(this, glyph = { glyphs[it.ordinal] }, label = ::label, onEntry = ::chosen, onExpand = { menu?.let { show(it.expand()) } }, onClose = { show(null) })
        none = TextView(this).apply {
            typeface = slate(Typography.REGULAR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
            setTextColor(ListColors.Title)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 3, 0, 0)
            text = getString(R.string.no_tasks)
            visibility = View.GONE
        }
        // No status bar of Android's: the 9900 shows its title bar alone (sim-267).
        window.hideStatusBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ListColors.Background)
            addView(LinearLayout(this@TaskListActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TitleBar(this@TaskListActivity, getString(R.string.title)), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(field, LinearLayout.LayoutParams(match, SearchField.HEIGHT))
                addView(FrameLayout(this@TaskListActivity).apply {
                    addView(list, FrameLayout.LayoutParams(match, match))
                    // "* No Tasks *" under Add Task, centred (sim-267, C; A).
                    addView(none, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = LineItemView.HEIGHT })
                }, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        opened(intent)
        graph.io.post {
            val menus = ListAct.entries.map { a -> glyphOf(a)?.let { themeBitmap(applicationContext, it) } }
            // net_rim_Task_Status-icons27x27.png: five 27 px cells; net_rim_Priority-icons9x27.png: three 9 px ones.
            val statuses = themeBitmap(applicationContext, "tsk_status")
            val priorities = themeBitmap(applicationContext, "tsk_priority")
            val pairs = Priority.entries.flatMap { p ->
                Status.entries.map { st ->
                    Bitmap.createBitmap(STATUS_X + STATUS, STATUS, Bitmap.Config.ARGB_8888).also { b ->
                        val c = Canvas(b)
                        c.drawBitmap(Bitmap.createBitmap(priorities, p.cell * PRIORITY, 0, PRIORITY, STATUS), 0f, 0f, null)
                        c.drawBitmap(Bitmap.createBitmap(statuses, st.cell * STATUS, 0, STATUS, STATUS), STATUS_X.toFloat(), 0f, null)
                    }
                }
            }
            runOnUiThread {
                menus.forEachIndexed { i, b -> glyphs[i] = b }
                images = pairs
                list.invalidateViews()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opened(intent)
    }

    /** Calendar's New Task (sim-223): the blank form over the list, once per intent. Only shows a form, saves nothing. */
    private fun opened(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_NEW, false)) return
        intent.removeExtra(EXTRA_NEW)
        act(ListAct.NEW)
    }

    override fun onStart() {
        super.onStart()
        sweep(this, "opened")
        load()
        // A reminder that cannot ring is said, never silent.
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) status(this, getString(R.string.no_notifications))
    }

    // The task just created takes the focus, now or once the list has read it (ListEvent.Created).
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val id = data?.getLongExtra(EXTRA_SAVED, -1L) ?: -1L
        if (requestCode == NEW_TASK && resultCode == RESULT_OK && id >= 0) update(ListEvent.Created(id))
    }

    /** The database and the options read again off the main thread (the screen came back from the form or the options). */
    private fun load() {
        val g = graph
        g.io.post {
            val tasks = g.tasks.all()
            val o = g.options
            runOnUiThread {
                options = o
                now = LocalDateTime.now()
                update(ListEvent.Loaded(tasks, o.sort))
            }
        }
    }

    /** [change] written off the main thread, then the reminders armed again and the list read. */
    private fun write(change: (Tasks) -> Boolean) {
        val g = graph
        g.io.post {
            val ok = change(g.tasks)
            runOnUiThread { if (ok) load() else status(this, getString(R.string.not_updated)) }
        }
        sweep(this, "changed")
    }

    private fun update(e: ListEvent) = set(state.reduce(e))

    private fun set(next: TaskList) {
        val old = state
        state = next
        if (next.typed != old.typed) field.setText(next.field)
        if (next.rows != old.rows) adapter.notifyDataSetChanged() else if (next.focus != old.focus) list.invalidateViews()
        none.visibility = if (next.loaded && next.shown.isEmpty()) View.VISIBLE else View.GONE
        if (next.focus < list.firstVisiblePosition || next.focus > list.lastVisiblePosition) list.setSelection(next.focus)
    }

    // Actions

    private fun open() {
        when (state.focused) {
            AddTask -> act(ListAct.NEW)
            is Entry -> act(ListAct.OPEN)
            null -> Unit
        }
    }

    private fun act(a: ListAct) {
        val t = (state.focused as? Entry)?.task
        when (a) {
            ListAct.NEW -> startActivityForResult(Intent(this, EditActivity::class.java), NEW_TASK)
            ListAct.OPEN -> t?.let { startActivity(EditActivity.open(this, it.id)) }
            // Mark Completed (guide p. 207, B): a recurring task moves to its next occurrence ([completed]).
            ListAct.COMPLETE -> t?.let { done -> write { it.get(done.id)?.let { cur -> it.save(cur.completed(System.currentTimeMillis())) } != null } }
            ListAct.PROGRESS -> t?.let { going -> write { it.get(going.id)?.let { cur -> it.save(cur.copy(status = Status.IN_PROGRESS, modified = System.currentTimeMillis())) } != null } }
            ListAct.DELETE -> t?.let { gone -> ask(R.string.delete_question) { write { it.delete(gone.id).also { ok -> if (ok) graph.ringer.cancel(gone.id) } } } }
            ListAct.DELETE_COMPLETED -> ask(R.string.delete_completed_question) { write { it.deleteCompleted(); true } }
            ListAct.HIDE, ListAct.SHOW -> update(ListEvent.ToggleHidden)
            ListAct.OPTIONS -> startActivity(Intent(this, OptionsActivity::class.java))
            ListAct.HELP -> dev.bold7.ui.Help.open(this, "tasks")
            ListAct.CLOSE -> finish()
            ListAct.SWITCH -> SwitchApplication.start(this)
        }
    }

    /** Delete's question, Cancel focused (A; sim-199, C, as Contacts'), unless Tasks Options' Confirm Delete is off (B). */
    private fun ask(question: Int, then: () -> Unit) {
        if (!options.confirmDelete) return then()
        Bold7Dialog(this, getString(question), listOf(getString(R.string.act_delete), getString(R.string.cancel)), DialogIcon.QUESTION, focused = 1, onButton = { b ->
            if (b == 0) then()
        }).show()
    }

    // Menus

    private fun label(a: ListAct) = getString(when (a) {
        ListAct.NEW -> R.string.act_new
        ListAct.COMPLETE -> R.string.act_complete
        ListAct.PROGRESS -> R.string.act_progress
        ListAct.OPEN -> R.string.act_open
        ListAct.DELETE -> R.string.act_delete
        ListAct.DELETE_COMPLETED -> R.string.act_delete_completed
        ListAct.HIDE -> R.string.act_hide
        ListAct.SHOW -> R.string.act_show
        ListAct.OPTIONS -> R.string.act_options
        ListAct.HELP -> R.string.act_help
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
            AddTask -> Long.MIN_VALUE
            is Entry -> r.task.id
        }
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView as? LineItemView ?: LineItemView(this@TaskListActivity)
            val lit = position == state.focus
            when (val r = state.rows[position]) {
                AddTask -> v.bind(getString(R.string.add_task), lit)
                is Entry -> {
                    val t = r.task
                    val image = images.getOrNull(t.priority.ordinal * Status.entries.size + t.status.ordinal)
                    v.bind(t.title.ifEmpty { getString(R.string.no_title) }, lit, image, TEXT_X, if (t.overdue(now, java.time.ZoneId.systemDefault())) OVERDUE else ListColors.Title)
                }
            }
            return v
        }
    }

    private companion object {
        const val STATUS = 27
        const val PRIORITY = 9
        /** sim-280 (C): the status image from x 14 to 41, the title from x 46. */
        const val STATUS_X = 14
        const val TEXT_X = 46
        /** An overdue task's title: the red of the priority's "!" (net_rim_Priority-icons9x27.png, A; its use D). */
        const val OVERDUE = 0xFFFD0000.toInt()
    }
}

/** Set by Calendar's New Task: open the new task form. */
const val EXTRA_NEW = "dev.bold7.tasks.NEW"

/** The id of the task the form has just created, in its result. */
const val EXTRA_SAVED = "dev.bold7.tasks.SAVED"

private const val NEW_TASK = 1

/** The short and full menus' image of each action (sm_*.png of the theme, A; the match D); the others have none. */
fun glyphOf(a: ListAct): String? = when (a) {
    ListAct.NEW -> "tsk_sm_new_task"
    ListAct.OPEN -> "tsk_sm_open"
    ListAct.DELETE -> "tsk_sm_delete"
    ListAct.COMPLETE -> "tsk_sm_mark_completed"
    else -> null
}
