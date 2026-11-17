package dev.bold7.essai

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import dev.bold7.design.ListColors
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.CheckBoxView
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.ListHeaderView
import dev.bold7.ui.ListItemView
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.ScreenStack
import dev.bold7.ui.SearchField
import dev.bold7.ui.TitleBar
import dev.bold7.ui.bb10List
import dev.bold7.ui.confirm
import dev.bold7.ui.shortcuts
import dev.bold7.ui.status
import dev.bold7.ui.themeBitmap
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The components of core/ui on one screen, for the captures beside the 9900 simulator's
 * (docs/BBOS7-VISUEL.md): a screen of Options' kind, its title, its search field and a call log of
 * [EXTRA_ROWS] made-up calls (500) under date headers. The Menu key opens the full menu (sim-44),
 * the trackpad held or a long touch on a row the short menu (sim-52), Full Menu or Menu on it the
 * full one; the menus open the dialogs with one, two and three buttons (sim-62, sim-19, sim-47), a
 * status message (sim-42), the check boxes (sim-60) and a call's page (ScreenStack); Back or Escape
 * closes and returns. The trackpad moves a focus on the search field, the headers and the rows, T
 * and B go to the top and the bottom. Nothing is read from the phone: the calls are made up.
 *
 * `su -c 'am start -n dev.bold7.essai/.DemoActivity [--ei rows 500] [--es show menu|short|dialog1|dialog2|dialog3|status|checks]'`
 */
class DemoActivity : Activity() {
    private enum class Act {
        SWITCH, OPEN, DELETE, MARK_OPENED, SEARCH, TO_TOP, TO_BOTTOM, COMPOSE,
        DIALOG_1, DIALOG_2, DIALOG_3, STATUS, CHECKS,
    }

    private sealed interface Screen {
        data object Log : Screen
        data class Call(val line: Line.Call) : Screen
        data object Checks : Screen
    }

    private sealed interface Line {
        data class Day(val title: String) : Line
        data class Call(val id: Int, val name: String, val number: String) : Line
    }

    // The full menu: Switch Application first, as on every OS 7 menu (C), then this screen's groups.
    private val full = listOf(
        MenuEntry(Act.SWITCH), MenuEntry(Act.OPEN, separatorAbove = true), MenuEntry(Act.DELETE),
        MenuEntry(Act.SEARCH, separatorAbove = true), MenuEntry(Act.TO_TOP), MenuEntry(Act.TO_BOTTOM),
        MenuEntry(Act.DIALOG_1, separatorAbove = true), MenuEntry(Act.DIALOG_2), MenuEntry(Act.DIALOG_3), MenuEntry(Act.STATUS),
        MenuEntry(Act.CHECKS, separatorAbove = true), MenuEntry(Act.COMPOSE),
    )

    // The short menu of a row: eight actions and Full Menu, three rows as sim-52.
    private val short = listOf(
        MenuEntry(Act.OPEN), MenuEntry(Act.DELETE), MenuEntry(Act.MARK_OPENED), MenuEntry(Act.SEARCH),
        MenuEntry(Act.TO_TOP), MenuEntry(Act.TO_BOTTOM), MenuEntry(Act.COMPOSE), MenuEntry(Act.SWITCH),
    )
    private val keys = shortcuts('t' to Act.TO_TOP, 'b' to Act.TO_BOTTOM)

    private var stack = ScreenStack<Screen>(Screen.Log)
    private var all: List<Line> = emptyList()
    private var lines: List<Line> = emptyList()
    private var focused: Int = 0 // a line, or SEARCH for the search field
    private var menu: Menu<Act>? = null
    private var held = false // the trackpad's click held into a short menu: its release is not a click

    private val checks = listOf("Enable:", "Prompt on Application Install:", "Allow Outgoing Calls While Locked:", "Lock Handheld Upon Holstering:")
    private val checked = BooleanArray(checks.size)
    private var checkFocus = 0

    private val glyphs = arrayOfNulls<Bitmap>(Act.entries.size)

    private lateinit var list: ListView
    private lateinit var search: SearchField
    private lateinit var log: View
    private lateinit var callPage: View
    private lateinit var callTitle: TitleBar
    private lateinit var callRow: ListItemView
    private lateinit var checksPage: View
    private lateinit var checkViews: List<CheckBoxView>
    private lateinit var menuView: MenuView<Act>
    private val adapter = Adapter()
    private val decoder = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deliberately simple: made up on the main thread, under a millisecond for 500 rows; a real app reads its provider off it.
        all = madeUp(intent.getIntExtra(EXTRA_ROWS, 500))
        lines = all
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        search = SearchField(this, "Search").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) = filter(s.toString())
            })
        }
        list = bb10List(ListView(this), 0).apply {
            adapter = this@DemoActivity.adapter
            isFocusable = false // the trackpad's focus is drawn by the rows (dispatchKeyEvent)
            isHapticFeedbackEnabled = false
            setOnItemClickListener { _, _, position, _ -> open(position) }
            setOnItemLongClickListener { _, _, position, _ ->
                focus(position)
                holdLine()
            }
        }
        log = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ListColors.Background)
            addView(TitleBar(this@DemoActivity, "Bold7"), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
            addView(search, LinearLayout.LayoutParams(match, SearchField.HEIGHT))
            addView(list, LinearLayout.LayoutParams(match, 0, 1f))
        }
        callTitle = TitleBar(this)
        callRow = ListItemView(this)
        callPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ListColors.Background)
            addView(callTitle, LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
            addView(callRow, LinearLayout.LayoutParams(match, ListItemView.HEIGHT))
        }
        checkViews = checks.indices.map { i -> CheckBoxView(this).apply { setOnClickListener { toggle(i) } } }
        checksPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ListColors.Background)
            addView(TitleBar(this@DemoActivity, "Password"), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
            checkViews.forEach { addView(it, LinearLayout.LayoutParams(match, CheckBoxView.HEIGHT)) }
        }
        menuView = MenuView(
            this,
            glyph = { glyphs[it.ordinal] },
            label = ::label,
            onEntry = ::chosen,
            onExpand = { menu?.let { show(it.expand()) } },
            onClose = { show(null) },
        )
        setContentView(FrameLayout(this).apply {
            addView(log)
            addView(callPage)
            addView(checksPage)
            addView(menuView)
        })
        render()
        loadGlyphs()
        window.decorView.post { showFromIntent(intent.getStringExtra(EXTRA_SHOW)) }
    }

    /** What `--es show` opens right away, for a capture. */
    private fun showFromIntent(what: String?) {
        when (what) {
            "menu" -> show(Menu(full, highlighted = 1))
            "short" -> show(Menu(short, line = focused, highlighted = 0))
            "dialog1" -> perform(Act.DIALOG_1)
            "dialog2" -> perform(Act.DIALOG_2)
            "dialog3" -> perform(Act.DIALOG_3)
            "status" -> perform(Act.STATUS)
            "checks" -> perform(Act.CHECKS)
        }
    }

    /** The short menu's images, sm_*.png of the theme (82x55), decoded off the main thread. */
    private fun loadGlyphs() = decoder.execute {
        val images = mapOf(
            Act.OPEN to "ui_sm_open", Act.DELETE to "ui_sm_delete", Act.MARK_OPENED to "ui_sm_mark_opened",
            Act.SEARCH to "ui_sm_search", Act.TO_TOP to "ui_sm_to_top", Act.TO_BOTTOM to "ui_sm_to_bottom",
            Act.COMPOSE to "ui_sm_compose", Act.SWITCH to "ui_sm_switch_apps",
        ).mapValues { themeBitmap(applicationContext, it.value) }
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            images.forEach { (act, bitmap) -> glyphs[act.ordinal] = bitmap }
            menuView.invalidate()
        }
    }

    override fun onDestroy() {
        decoder.shutdownNow()
        super.onDestroy()
    }

    private fun label(act: Act) = when (act) {
        Act.SWITCH -> "Switch Application"
        Act.OPEN -> "Open"
        Act.DELETE -> getString(R.string.demo_delete)
        Act.MARK_OPENED -> "Mark Opened"
        Act.SEARCH -> "Search"
        Act.TO_TOP -> "To Top"
        Act.TO_BOTTOM -> "To Bottom"
        Act.COMPOSE -> "Compose"
        Act.DIALOG_1 -> "Dialog, One Button"
        Act.DIALOG_2 -> "Dialog, Two Buttons"
        Act.DIALOG_3 -> "Dialog, Three Buttons"
        Act.STATUS -> "Status"
        Act.CHECKS -> "Check Boxes"
    }

    private fun render() {
        val top = stack.top
        log.visibility = if (top == Screen.Log) View.VISIBLE else View.GONE
        callPage.visibility = if (top is Screen.Call) View.VISIBLE else View.GONE
        checksPage.visibility = if (top == Screen.Checks) View.VISIBLE else View.GONE
        if (top is Screen.Call) {
            callTitle.bind(top.line.name)
            callRow.bind(top.line.name, top.line.number, lit = true)
        }
        // A box is disabled while Enable is not checked, as sim-60's.
        checkViews.forEachIndexed { i, v -> v.bind(checks[i], checked[i], lit = i == checkFocus, enabled = i == 0 || checked[0]) }
    }

    private fun push(screen: Screen) {
        show(null)
        stack = stack.push(screen)
        render()
    }

    private fun open(position: Int) {
        val line = lines.getOrNull(position) as? Line.Call ?: return
        push(Screen.Call(line))
    }

    private fun holdLine(): Boolean {
        if (lines.getOrNull(focused) !is Line.Call) return false
        show(Menu(short, line = focused, highlighted = 0))
        return true
    }

    private fun show(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val m = menu ?: return
        val act = m.entries.getOrNull(index)?.action ?: return
        show(null)
        perform(act)
    }

    private fun perform(act: Act) {
        when (act) {
            Act.OPEN -> open(focused)
            Act.TO_TOP -> moveTo(0)
            Act.TO_BOTTOM -> moveTo(lines.lastIndex)
            Act.SEARCH -> focus(SEARCH)
            Act.DELETE -> {
                val call = lines.getOrNull(focused) as? Line.Call ?: return
                confirm(this, R.string.demo_delete_title, R.string.demo_delete_message, R.string.demo_cancel, R.string.demo_delete) {
                    all = withoutEmptyDays(all - call)
                    lines = withoutEmptyDays(lines - call)
                    focused = focused.coerceAtMost(lines.lastIndex)
                    adapter.notifyDataSetChanged()
                }
            }
            // The three dialogs of the captures, their texts and buttons as the 9900 wrote them.
            Act.DIALOG_1 -> Bold7Dialog(this, "The password cannot be a sequence.", listOf("OK"), DialogIcon.EXCLAMATION, onButton = {}).show()
            Act.DIALOG_2 -> Bold7Dialog(this, "You have 2 new missed calls.", listOf("View", "Close"), onButton = {}).show()
            Act.DIALOG_3 -> Bold7Dialog(this, "Changes made!", listOf("Save", "Discard", "Cancel"), DialogIcon.QUESTION, onButton = {}).show()
            Act.STATUS -> status(this, "Screen and Keypad are locked. Press the Lock button to unlock")
            Act.CHECKS -> push(Screen.Checks)
            Act.SWITCH, Act.MARK_OPENED, Act.COMPOSE -> Unit // nothing to do in a demo
        }
    }

    private fun toggle(i: Int) {
        if (i != 0 && !checked[0]) return
        checked[i] = !checked[i]
        checkFocus = i
        render()
    }

    private fun filter(query: String) {
        lines = if (query.isBlank()) all else withoutEmptyDays(all.filter { it is Line.Day || (it as Line.Call).name.contains(query, ignoreCase = true) })
        adapter.notifyDataSetChanged()
    }

    private fun moveTo(position: Int) {
        if (position >= 0) focus(position)
    }

    /** The trackpad's focus on line [position] kept in sight, or on the search field. */
    private fun focus(position: Int) {
        focused = position
        if (position == SEARCH) {
            search.requestFocus()
        } else {
            search.clearFocus()
            val child = list.getChildAt(position - list.firstVisiblePosition)
            if (child == null || child.top < 0 || child.bottom > list.height) list.setSelection(position)
        }
        adapter.notifyDataSetChanged()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val click = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        val back = e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_ESCAPE
        // The click acts on its release, unless held into a short menu (ViewConfiguration's long press).
        if (e.action == KeyEvent.ACTION_UP) {
            if (!click) return super.dispatchKeyEvent(e)
            when {
                held -> held = false
                menu == null -> clicked()
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            val key = when {
                e.keyCode == KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                click -> MenuKey.CLICK
                back -> MenuKey.BACK
                e.keyCode == KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> return true
            }
            when (val r = m.key(key)) {
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> show(null)
            }
            return true
        }
        if (back) {
            stack = stack.pop() ?: return super.dispatchKeyEvent(e)
            render()
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_MENU) {
            show(Menu(full, highlighted = 1))
            return true
        }
        if (click) {
            // Held: its first repeat comes after the long press timeout.
            if ((e.isLongPress || e.repeatCount == 1) && stack.top == Screen.Log && focused != SEARCH) held = holdLine()
            return true
        }
        return when (stack.top) {
            Screen.Log -> logKey(e) || super.dispatchKeyEvent(e)
            Screen.Checks -> checksKey(e) || super.dispatchKeyEvent(e)
            is Screen.Call -> super.dispatchKeyEvent(e)
        }
    }

    private fun clicked() {
        when (stack.top) {
            Screen.Log -> if (focused != SEARCH) open(focused)
            Screen.Checks -> toggle(checkFocus)
            is Screen.Call -> Unit
        }
    }

    private fun logKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> focus(if (focused <= 0) SEARCH else focused - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (focused < lines.lastIndex) focus(focused + 1)
            else -> keys.of(e.displayLabel, textFocused = focused == SEARCH)?.let(::perform) ?: return false
        }
        return true
    }

    private fun checksKey(e: KeyEvent): Boolean {
        checkFocus = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> (checkFocus - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> (checkFocus + 1).coerceAtMost(checks.lastIndex)
            KeyEvent.KEYCODE_SPACE -> checkFocus.also { toggle(it) }
            else -> return false
        }
        render()
        return true
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = lines.size
        override fun getItem(position: Int) = lines[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (lines[position] is Line.Day) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = lines[position] is Line.Call

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val line = lines[position]) {
            is Line.Day -> ((convertView as? ListHeaderView) ?: ListHeaderView(this@DemoActivity)).apply { show(line.title, lit = position == focused) }
            is Line.Call -> ((convertView as? ListItemView) ?: ListItemView(this@DemoActivity)).apply {
                bind(line.name, line.number, lit = position == focused)
            }
        }
    }

    private companion object {
        const val EXTRA_ROWS = "rows"
        const val EXTRA_SHOW = "show"
        const val SEARCH = -1

        val names = listOf("Kelly Nielsen", "Emma Kinsella", "Bern Paterson", "Albert Johnson", "Alice Martin", "Paul Durand", "Sophie Leroy", "Marc Petit")

        // OS 7's date headers: "Fri 16 Sep 2011" (gsm 027, C).
        val days: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK)

        /** [n] made-up calls, newest first from the simulator's Friday 13 November 2026, a date header every ten. */
        fun madeUp(n: Int): List<Line> = buildList {
            for (i in 0 until n) {
                if (i % 10 == 0) add(Line.Day(LocalDate.of(2026, 11, 13).minusDays(i / 10L).format(days)))
                add(Line.Call(i, names[i % names.size], "Mobile: +1 (519) 555 01%02d".format(i % 100)))
            }
        }

        /** [lines] without the headers left with no call under them. */
        fun withoutEmptyDays(lines: List<Line>): List<Line> = lines.filterIndexed { i, line -> line !is Line.Day || lines.getOrNull(i + 1) is Line.Call }
    }
}
