package dev.bold7.calculator

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.withSwitchApplication
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.status

/** What the menu does: a conversion's direction, a conversion, help or leaving. */
sealed interface Item {
    data class Direction(val toMetric: Boolean) : Item
    data class Convert(val c: Conversion, val toMetric: Boolean) : Item
    data object Help : Item
    data object Switch : Item
    data object Close : Item
}

/**
 * The calculator of BBOS 7.1 (docs/BBOS7-VISUEL.md §31, §34.1): the trackpad moves over the keys
 * and its click presses one, the keyboard types (digits, + - x / . = %, Enter for =, Delete to
 * erase, C to clear); the Menu key opens To Metric, From Metric (the guide's, p. 362, B, then the
 * nine conversions "in -> cm" of sim-251 to sim-253, C), Help and Close (sim-250, C; Switch
 * Application is the shared menu's, not here). The memory is on the M keys. Escape clears, then
 * leaves. Renders [Calc]; nothing is stored. No title bar (sim-249, C).
 */
class CalcActivity : Activity() {
    private lateinit var view: CalcView
    private lateinit var menuView: MenuView<Item>
    private var calc = Calc()
    private var focus = Keypad.NO_FOCUS
    private var menu: Menu<Item>? = null
    private var held = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        view = CalcView(this) { press(it) }
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = ::chosen, onExpand = {}, onClose = { show(null) })
        // No status bar of Android's: the 9900 shows its display from the top edge, no bar at all (sim-249).
        window.hideStatusBar()
        setContentView(FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(match, match))
            addView(menuView)
        })
        render()
    }

    private fun render() = view.bind(calc, focus)

    private fun press(k: Key) {
        calc = calc.press(k)
        render()
    }

    private fun label(i: Item): String = when (i) {
        is Item.Direction -> getString(if (i.toMetric) R.string.act_to_metric else R.string.act_from_metric)
        is Item.Convert -> i.c.label(i.toMetric)
        Item.Help -> getString(R.string.act_help)
        Item.Switch -> getString(R.string.act_switch_application)
        Item.Close -> getString(R.string.act_close)
    }

    private fun fullMenu() = Menu(
        listOf(
            MenuEntry(Item.Direction(true), submenu = true),
            MenuEntry(Item.Direction(false), submenu = true),
            MenuEntry(Item.Help, separatorAbove = true),
            MenuEntry(Item.Close),
        ),
        highlighted = 0,
    ).withSwitchApplication(Item.Switch)

    private fun types(toMetric: Boolean) = Menu<Item>(Conversion.entries.map { MenuEntry<Item>(Item.Convert(it, toMetric)) }, highlighted = 0)

    private fun show(m: Menu<Item>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val item = menu?.entries?.getOrNull(index)?.action ?: return
        when (item) {
            is Item.Direction -> if (calc.error) show(null) else show(types(item.toMetric))
            is Item.Convert -> {
                show(null)
                val before = calc.display
                calc = calc.convert(item.c, item.toMetric)
                render()
                val (from, to) = item.c.label(item.toMetric).split(" -> ")
                if (!calc.error) status(this, "$before $from = ${calc.display} $to")
            }
            // Help has no source for its text; closes the menu until one exists.
            Item.Help -> show(null)
            Item.Switch -> { show(null); SwitchApplication.start(this) }
            Item.Close -> show(null)
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (e.action == KeyEvent.ACTION_UP) return if (menu != null) true else super.dispatchKeyEvent(e)
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            val key = when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> if (click) MenuKey.CLICK else return true
            }
            when (val r = m.key(key)) {
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> show(null)
            }
            return true
        }
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Dir.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
            else -> null
        }
        when {
            dir != null -> {
                focus = Keypad.step(focus, dir)
                render()
            }
            code == KeyEvent.KEYCODE_DPAD_CENTER -> if (e.repeatCount == 0) {
                if (focus != Keypad.NO_FOCUS) {
                    view.flash(focus)
                    press(Keypad.cells[focus].key)
                }
            }
            // Enter is "=", the key of the keyboard (D); the trackpad's click presses the focused key.
            code == KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) press(Key.Equals)
            code == KeyEvent.KEYCODE_DEL -> press(Key.Back)
            code == KeyEvent.KEYCODE_MENU -> show(fullMenu())
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE -> {
                if (calc.pristine) return super.dispatchKeyEvent(e)
                press(Key.Clear)
            }
            else -> {
                val c = e.getUnicodeChar(e.metaState)
                val k = if (c > 0 && !Character.isISOControl(c)) keyOf(c.toChar()) else null
                if (k == null) return super.dispatchKeyEvent(e)
                if (e.repeatCount == 0) {
                    Keypad.cells.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let(view::flash)
                    press(k)
                }
            }
        }
        return true
    }
}
