package dev.bold7.memopad

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.CheckBoxView
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.TitleBar
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.textPaint

/**
 * MemoPad Options as sim-263 and sim-264 show them (C): the title "MemoPad Options", a white group
 * "Actions" on the grey page with the check box "Confirm Delete:" (checked by default, focused) and
 * "Number of Entries:" with its value at the right (the memos' count, read only: D). Menu: Save,
 * Close (sim-264); Save when leaving with changes ("Changes made!", sim-47, C). No sort option.
 */
class OptionsActivity : Activity() {
    private enum class Act { SAVE, CLOSE }

    private var saved = MemoOptions()
    private var options = saved
    private var menu: Menu<Act>? = null
    private lateinit var confirm: CheckBoxView
    private lateinit var entries: ValueRow
    private lateinit var menuView: MenuView<Act>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        confirm = CheckBoxView(this).apply { setOnClickListener { toggle() } }
        entries = ValueRow(this, getString(R.string.opt_entries))
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ListColors.Background)
                setStroke(1, BOX_BORDER)
            }
            setPadding(1, 1, 1, 1)
            addView(ValueRow(this@OptionsActivity, getString(R.string.opt_actions), group = true), LinearLayout.LayoutParams(match, GROUP_TITLE))
            addView(confirm, LinearLayout.LayoutParams(match, CheckBoxView.HEIGHT))
            addView(entries, LinearLayout.LayoutParams(match, CheckBoxView.HEIGHT))
        }
        menuView = MenuView(this, glyph = { null }, label = { getString(if (it == Act.SAVE) R.string.act_save else R.string.act_close) }, onEntry = ::chosen, onExpand = {}, onClose = { show(null) })
        // No status bar of Android's: the 9900 shows its title bar alone (sim-254).
        window.hideStatusBar()
        setContentView(FrameLayout(this).apply {
            addView(LinearLayout(this@OptionsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ListColors.GroupBackground)
                addView(TitleBar(this@OptionsActivity, getString(R.string.options_title), options = true), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(group, LinearLayout.LayoutParams(match, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(5, 4, 5, 0) })
            })
            addView(menuView)
        })
        render()
        val g = graph
        g.io.post {
            val read = g.options
            val count = g.memos.all().size
            runOnUiThread {
                saved = read
                options = read
                entries.value = count.toString()
                render()
            }
        }
    }

    private fun render() = confirm.bind(getString(R.string.opt_confirm_delete), options.confirmDelete, lit = true)

    private fun toggle() {
        options = options.copy(confirmDelete = !options.confirmDelete)
        render()
    }

    private fun show(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val act = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        if (act == Act.SAVE) save() else leave()
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
        val g = graph
        g.io.post {
            g.options = o
            runOnUiThread {
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
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> show(null)
            }
            return true
        }
        when {
            back -> leave()
            e.keyCode == KeyEvent.KEYCODE_MENU -> show(Menu(listOf(MenuEntry(Act.SAVE), MenuEntry(Act.CLOSE)), highlighted = 0))
            click || e.keyCode == KeyEvent.KEYCODE_SPACE -> toggle()
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    private companion object {
        const val BOX_BORDER = 0xFFC6C6C6.toInt()
        const val GROUP_TITLE = 36
    }
}

/** A row of the group: its title ([group]: bold grey over a line, sim-263, C) or a label with its value at the right, not focusable. */
private class ValueRow(context: Context, private val label: String, private val group: Boolean = false) : View(context) {
    var value = ""
        set(v) {
            field = v
            refit()
            invalidate()
        }

    private val text = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.CheckText)
    private val title = textPaint(Typography.SUBTITLE, Typography.BOLD, ListColors.Subtitle)
    private val valuePaint = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.Subtitle, align = Paint.Align.RIGHT)
    private val line = fillPaint(ListColors.Divider)
    private var shownLabel = label
    private var shownValue = ""

    // Cut by fit, here and never in onDraw.
    private fun refit() {
        if (width == 0) return
        if (group) {
            shownLabel = title.fit(label, Rooms.groupTitle(width))
        } else {
            shownValue = valuePaint.fit(value, Rooms.value(width))
            shownLabel = text.fit(label, Rooms.label(width, valuePaint.measureText(shownValue)))
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
