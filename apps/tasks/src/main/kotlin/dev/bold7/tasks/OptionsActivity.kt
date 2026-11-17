package dev.bold7.tasks

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.CheckBoxView
import dev.bold7.ui.ChoiceList
import dev.bold7.ui.ChoiceListView
import dev.bold7.ui.ChoiceView
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.withSwitchApplication
import dev.bold7.ui.TitleBar
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.textPaint

/**
 * Tasks Options (A: the title, Sort By and its four values; Snooze, A: SNOOZE_CHOICES in the Tasks'
 * .cod; Confirm Delete, guide p. 211, B), drawn as MemoPad Options (sim-263, C: a white box on the
 * grey page, Confirm Delete a check box): Sort By and Snooze as choice fields, Confirm Delete
 * checked by default (sim-263). The rows' order and the values of Snooze (the Clock's) are D, until
 * a capture of Tasks Options. Saved by Menu > Save, or Save when leaving with changes ("Changes
 * made!", sim-47), as every options screen of OS 7. Number of Entries is the tasks' count, read only (sim-320, C).
 */
class OptionsActivity : Activity() {
    private enum class Act { SWITCH, SAVE, CLOSE }

    private val graph get() = application.graph
    private var saved = TaskOptions()
    private var options = saved
    /** 0 Sort By, 1 Snooze, 2 Confirm Delete. */
    private var focused = 0
    private var list: ChoiceList? = null
    private var menu: Menu<Act>? = null

    private lateinit var sort: ChoiceView
    private lateinit var snooze: ChoiceView
    private lateinit var confirm: CheckBoxView
    private lateinit var entries: ValueRow
    private lateinit var listView: ChoiceListView
    private lateinit var menuView: MenuView<Act>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        sort = ChoiceView(this).apply { setOnClickListener { focus(0); openList() } }
        snooze = ChoiceView(this).apply { setOnClickListener { focus(1); openList() } }
        confirm = CheckBoxView(this).apply { setOnClickListener { focus(2); toggle() } }
        entries = ValueRow(this, getString(R.string.opt_entries))
        // The rows draw their focus themselves: no grey highlight of Android's under the focused one.
        listOf<View>(sort, snooze, confirm).forEach { it.defaultFocusHighlightEnabled = false }
        // Views, Actions, then the count alone in a box of its own (sim-320, C).
        fun box(vararg rows: Pair<View, Int>) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ListColors.Background)
                setStroke(1, EditActivity.BOX_BORDER)
            }
            setPadding(1, 1, 1, 1)
            rows.forEach { (v, h) -> addView(v, LinearLayout.LayoutParams(match, h)) }
        }
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val title = { s: Int -> ValueRow(this, getString(s), group = true) to GROUP_TITLE }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ListColors.GroupBackground)
            addView(TitleBar(this@OptionsActivity, getString(R.string.options_title), options = true), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
            addView(box(title(R.string.opt_views), sort to ChoiceView.HEIGHT), LinearLayout.LayoutParams(match, wrap).apply { setMargins(5, 4, 5, 0) })
            addView(box(title(R.string.opt_actions), snooze to ChoiceView.HEIGHT, confirm to ChoiceView.HEIGHT), LinearLayout.LayoutParams(match, wrap).apply { setMargins(5, 5, 5, 0) })
            addView(box(entries to CheckBoxView.HEIGHT), LinearLayout.LayoutParams(match, wrap).apply { setMargins(5, 5, 5, 0) })
        }
        listView = ChoiceListView(this, onChoose = ::choose, onCancel = { showList(null) })
        menuView = MenuView(this, glyph = { null }, label = { getString(when (it) { Act.SWITCH -> R.string.act_switch_application; Act.SAVE -> R.string.act_save; Act.CLOSE -> R.string.act_close }) }, onEntry = ::chosen, onExpand = {}, onClose = { showMenu(null) })
        // No status bar of Android's: the 9900 shows its title bar alone (sim-267).
        window.hideStatusBar()
        setContentView(FrameLayout(this).apply {
            addView(page)
            addView(listView)
            addView(menuView)
        })
        render()
        graph.io.post {
            val read = graph.options
            val count = graph.tasks.all().size
            mainExecutor.execute {
                saved = read
                options = read
                entries.value = count.toString()
                render()
            }
        }
    }

    private fun focus(row: Int) {
        focused = row
        render()
    }

    private fun render() {
        val idle = list == null
        sort.bind(getString(R.string.opt_sort), label(options.sort), lit = focused == 0 && idle)
        snooze.bind(getString(R.string.opt_snooze), snoozeLabel(options.snooze), lit = focused == 1 && idle)
        confirm.bind(getString(R.string.opt_confirm_delete), options.confirmDelete, lit = focused == 2)
    }

    private fun toggle() {
        options = options.copy(confirmDelete = !options.confirmDelete)
        render()
    }

    private fun openList() {
        if (focused == 2) return toggle()
        val row = if (focused == 0) sort else snooze
        val at = IntArray(2).also { row.getLocationInWindow(it) }
        val origin = IntArray(2).also { listView.getLocationInWindow(it) }
        val (values, chosen) = if (focused == 0) SortBy.entries.map { label(it) } to options.sort.ordinal
        else SNOOZE_LIST.map { snoozeLabel(it) } to SNOOZE_LIST.indexOf(options.snooze).coerceAtLeast(0)
        val l = ChoiceList(values.size, chosen)
        list = l
        listView.open(values, l, at[0] - origin[0] + row.fieldLeft, at[1] - origin[1])
        render()
    }

    private fun showList(l: ChoiceList?) {
        list = l
        listView.show(l)
        render()
    }

    private fun choose(index: Int) {
        options = if (focused == 0) options.copy(sort = SortBy.entries[index]) else options.copy(snooze = SNOOZE_LIST[index])
        showList(null)
    }

    private fun showMenu(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val act = menu?.entries?.getOrNull(index)?.action ?: return
        showMenu(null)
        when (act) {
            Act.SWITCH -> SwitchApplication.start(this)
            Act.SAVE -> save()
            Act.CLOSE -> leave()
        }
    }

    private fun leave() {
        if (options == saved) return finish()
        Bold7Dialog(this, getString(R.string.changes_made), listOf(getString(R.string.act_save), getString(R.string.discard), getString(R.string.cancel)), DialogIcon.QUESTION, onButton = {
            when (it) {
                0 -> save()
                1 -> finish()
            }
        }).show()
    }

    private fun save() {
        val o = options
        graph.io.post {
            graph.options = o
            mainExecutor.execute {
                saved = o
                finish()
            }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val click = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        val back = e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_ESCAPE
        if (click && e.repeatCount > 0) return true
        val l = list
        if (l != null) {
            when {
                e.keyCode == KeyEvent.KEYCODE_DPAD_UP -> showList(l.step(down = false))
                e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> showList(l.step(down = true))
                click -> choose(l.chosen)
                back -> showList(null)
            }
            return true
        }
        val m = menu
        if (m != null) {
            val key = when {
                e.keyCode == KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                click -> MenuKey.CLICK
                back -> MenuKey.BACK
                e.keyCode == KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> return true
            }
            when (val r = m.key(key)) {
                is MenuResult.Show -> showMenu(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> showMenu(null)
            }
            return true
        }
        when {
            back -> leave()
            e.keyCode == KeyEvent.KEYCODE_MENU -> showMenu(Menu(listOf(MenuEntry(Act.SAVE), MenuEntry(Act.CLOSE)), highlighted = 0).withSwitchApplication(Act.SWITCH))
            e.keyCode == KeyEvent.KEYCODE_DPAD_UP -> focus((focused - 1).coerceAtLeast(0))
            e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> focus((focused + 1).coerceAtMost(2))
            click || e.keyCode == KeyEvent.KEYCODE_SPACE -> openList()
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    private companion object {
        const val GROUP_TITLE = 36
    }
}

/** A row of a box: its title ([group]: bold grey over a line, sim-320, C) or a label with its value at the right, not focusable. */
private class ValueRow(context: Context, private val label: String, private val group: Boolean = false) : View(context) {
    var value = ""
        set(v) {
            field = v
            refit()
            invalidate()
        }

    private val text = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.CheckText)
    private val title = textPaint(Typography.SUBTITLE, Typography.BOLD, ListColors.Subtitle)
    private val valuePaint = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.CheckText, align = Paint.Align.RIGHT)
    private val line = fillPaint(ListColors.Divider)
    private var shownLabel = label
    private var shownValue = ""

    // Cut by fit, here and never in onDraw.
    private fun refit() {
        if (width == 0) return
        if (group) {
            shownLabel = title.fit(label, (width - 2 * ListDimens.TEXT_X).coerceAtLeast(0).toFloat())
        } else {
            shownValue = valuePaint.fit(value, (width / 2 - ListDimens.CHECK_RIGHT).coerceAtLeast(0).toFloat())
            shownLabel = text.fit(label, (width - ListDimens.CHECK_RIGHT - valuePaint.measureText(shownValue) - 2 * ListDimens.TEXT_X).coerceAtLeast(0f))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = refit()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (group) {
            canvas.drawText(shownLabel, ListDimens.TEXT_X.toFloat(), height - 8f, title)
            canvas.drawRect(0f, height - 1f, w, height.toFloat(), line)
        } else {
            canvas.drawText(shownLabel, ListDimens.TEXT_X.toFloat(), ListDimens.CHECK_BASELINE.toFloat(), text)
            canvas.drawText(shownValue, w - ListDimens.CHECK_RIGHT, ListDimens.CHECK_BASELINE.toFloat(), valuePaint)
        }
    }
}
