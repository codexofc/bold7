package dev.bold7.phone

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.bold7.design.ListColors
import dev.bold7.ui.ListItemView
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.TitleBar
import dev.bold7.ui.setContentUnderBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Status of the Phone's menu (sim-143, C; guide 7.1 p. 76, B: "Status > Last Call or Total Calls >
 * Clear Timer"): the two call timers, worked out from the call log ([timers]). Its look is D (no
 * capture): Options' rows under a title. The click or Menu offers Clear Timer for the focused timer,
 * done at once (D: the guide then saves with Menu > Save). Escape closes.
 */
class StatusActivity : Activity() {
    private val graph get() = (application as PhoneApp).graph
    private lateinit var scope: CoroutineScope
    private lateinit var menuView: MenuView<Unit>
    private lateinit var rows: List<ListItemView>
    private var calls: List<Timed> = emptyList()
    private var cleared = CallTimersCleared()
    private var focus = 0
    private var menu: Menu<Unit>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        rows = List(2) { ListItemView(this) }
        menuView = MenuView(this, glyph = { null }, label = { getString(R.string.act_clear_timer) }, onEntry = { chosen() }, onExpand = {}, onClose = { show(null) })
        setContentUnderBar(FrameLayout(this).apply {
            setBackgroundColor(ListColors.Background)
            addView(LinearLayout(this@StatusActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TitleBar(this@StatusActivity, getString(R.string.act_status)), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                rows.forEachIndexed { i, v ->
                    v.setOnClickListener { focus = i; render(); show(clearMenu()) }
                    addView(v, LinearLayout.LayoutParams(match, ListItemView.HEIGHT))
                }
            })
            addView(menuView)
        })
        scope = CoroutineScope(SupervisorJob() + graph.main)
        render()
        scope.launch {
            val (c, k) = withContext(graph.io) { graph.log.timed() to graph.timers.load() }
            calls = c
            cleared = k
            render()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val t = timers(calls, cleared)
        rows[0].bind(getString(R.string.status_last_call), elapsed(t.lastCall * 1000), lit = focus == 0)
        rows[1].bind(getString(R.string.status_total_calls), elapsed(t.total * 1000), lit = focus == 1)
    }

    private fun clearMenu() = Menu(listOf(MenuEntry(Unit)), highlighted = 0)

    private fun show(m: Menu<Unit>?) {
        menu = m
        menuView.bind(m)
    }

    /** Clear Timer: the focused timer counts from now, kept off the main thread. */
    private fun chosen() {
        show(null)
        val now = System.currentTimeMillis()
        val next = if (focus == 0) cleared.copy(lastCall = now) else cleared.copy(total = now)
        cleared = next
        render()
        scope.launch { withContext(graph.io) { graph.timers.save(next) } }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (e.action != KeyEvent.ACTION_DOWN) return if (click) true else super.dispatchKeyEvent(e)
        if (e.repeatCount > 0) return true
        val m = menu
        if (m != null) {
            when (val r = m.key(
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                    KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                    else -> if (click) MenuKey.CLICK else return true
                },
            )) {
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen()
                MenuResult.Close -> show(null)
            }
            return true
        }
        when {
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                focus = if (code == KeyEvent.KEYCODE_DPAD_UP) 0 else 1
                render()
            }
            code == KeyEvent.KEYCODE_MENU || click -> show(clearMenu())
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }
}
