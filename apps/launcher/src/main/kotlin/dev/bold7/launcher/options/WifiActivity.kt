package dev.bold7.launcher.options

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.launcher.graph
import dev.bold7.launcher.switcher.startSwitcher
import dev.bold7.platform.SystemWifi
import dev.bold7.platform.WifiNetworks
import dev.bold7.platform.WifiSaved
import dev.bold7.platform.WifiSeen
import dev.bold7.platform.WifiSecurity
import dev.bold7.platform.WifiState
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.CheckBoxView
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.PasswordDialog
import dev.bold7.ui.TitleBar
import dev.bold7.ui.status
import dev.bold7.ui.withSwitchApplication
import java.nio.CharBuffer

/**
 * Options > Networks and Connections > Wi-Fi Network (guide 7.1 p. 265 to 267, B):
 * the networks the last scan saw, with their lock and signal, Show Open networks only, Saved Wi-Fi
 * Networks (Connect, Delete). A click joins a network, its password typed in OS 7's text box,
 * masked; a saved one connects without it. The state is [WifiScreen] and [SavedWifiScreen] (pure,
 * WifiTest); Android's Wi-Fi is [WifiNetworks], read and written on the io thread. Other Ways to
 * Connect (WPS, a hidden network) is not drawn.
 *
 * Not exported: opened by Options, in its task.
 */
class WifiActivity : Activity() {
    private enum class Act { SWITCH, CONNECT, SCAN, SAVED, DELETE, CLOSE }

    private val io get() = graph.io
    private val wifi: WifiNetworks by lazy {
        if (intent.getBooleanExtra(EXTRA_DEMO, false)) DemoWifi else SystemWifi(applicationContext, graph.io)
    }
    private var screen = WifiScreen()
    private var saved: SavedWifiScreen? = null
    private var strips: IconRowView.Strips? = null
    private var menu: Menu<Act>? = null
    private var busy = false
    private var read = false

    private lateinit var title: TitleBar
    private lateinit var scroll: ScrollView
    private lateinit var rows: LinearLayout
    private lateinit var menuView: MenuView<Act>
    private var notice: NoteView? = null
    private var views: List<View> = emptyList()
    private var built: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        title = TitleBar(this, TITLE)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            // The focused scroll would draw the theme's 12 % focus highlight over the whole page; the activity draws its own focus.
            defaultFocusHighlightEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(ListColors.Background)
            addView(rows, ViewGroup.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = ::chosen, onExpand = {}, onClose = { show(null) })
        setContentView(FrameLayout(this).apply {
            addView(LinearLayout(this@WifiActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title, LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        window.hideStatusBar()
        io.post {
            val s = IconRowView.strips(this)
            runOnUiThread {
                strips = s
                render()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        wifi.start { state ->
            // The first state puts the focus on the first row; the next ones keep it where it is.
            screen = if (read) screen.with(state) else WifiScreen(state, screen.openOnly)
            read = true
            saved = saved?.with(state.saved)
            render()
        }
        // A fresh scan each time the screen comes back (Android throttles them).
        io.post { wifi.scan() }
    }

    override fun onStop() {
        wifi.stop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.hide(WindowInsets.Type.statusBars())
    }

    // Drawing

    private fun render() {
        val s = strips ?: return
        val list = saved
        val shape: Any = list?.rows?.size?.let { "saved" to it } ?: (screen.rows.map { it::class } to screen.notice)
        if (shape != built) build()
        built = shape
        title.bind(if (list == null) TITLE else SAVED_TITLE)
        if (list != null) {
            list.rows.forEachIndexed { i, n ->
                (views[i] as IconRowView).bind(n.ssid, securityLabel(n.security), i == list.focus, right = if (locked(n.security)) listOf(s.locked) else emptyList())
            }
            scrollTo(list.focus)
            return
        }
        screen.rows.forEachIndexed { i, r ->
            val lit = i == screen.focus
            when (r) {
                is WifiRow.Network -> (views[i] as IconRowView).bind(
                    r.ssid,
                    when {
                        r.connected -> "Connected"
                        r.saved != null -> "Saved"
                        else -> securityLabel(r.security)
                    },
                    lit,
                    right = listOfNotNull(s.bars(r.bars), s.locked.takeIf { locked(r.security) }),
                )
                is WifiRow.OpenOnly -> (views[i] as CheckBoxView).bind(OPEN_ONLY, r.checked, lit)
                WifiRow.Saved -> (views[i] as IconRowView).bind(SAVED_TITLE, "", lit)
            }
        }
        scrollTo(screen.focus)
    }

    private fun build() {
        rows.removeAllViews()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val list = saved
        val text = if (list != null) "No saved Wi-Fi networks.".takeIf { list.rows.isEmpty() } else screen.notice
        notice = text?.let { t -> NoteView(this).also { it.bind("", t, lit = false); rows.addView(it, LinearLayout.LayoutParams(match, wrap)) } }
        views = if (list != null) {
            list.rows.indices.map { i -> IconRowView(this).apply { setOnClickListener { tapSaved(i) } }.also { rows.addView(it, LinearLayout.LayoutParams(match, ListDimens.ROW)) } }
        } else {
            screen.rows.mapIndexed { i, r ->
                val v: View = if (r is WifiRow.OpenOnly) CheckBoxView(this) else IconRowView(this)
                v.setOnClickListener { click(i) }
                rows.addView(v, LinearLayout.LayoutParams(match, if (r is WifiRow.OpenOnly) CheckBoxView.HEIGHT else ListDimens.ROW))
                v
            }
        }
        scroll.scrollTo(0, 0)
    }

    private fun scrollTo(i: Int) {
        views.getOrNull(i)?.let { v -> v.post { v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), true) } }
    }

    // Acting

    private fun click(row: Int = screen.focus) {
        val (next, action) = screen.click(row)
        screen = next
        render()
        when (action) {
            WifiAction.None -> Unit
            WifiAction.OpenSaved -> openSaved()
            is WifiAction.Connect -> call("Connecting to ${action.ssid}...") { wifi.connect(action.id) }
            is WifiAction.Unsupported -> status(this, "${securityLabel(action.security)} networks are not supported.")
            is WifiAction.Join -> if (!action.password) {
                call("Connecting to ${action.ssid}...") { wifi.join(action.ssid, action.security, null) }
            } else {
                PasswordDialog(this, "Password for ${action.ssid}:", secure = wifi !== DemoWifi) { chars -> join(action.ssid, action.security, chars) }.show()
            }
        }
    }

    /** The password goes to Android's configuration and nowhere else; the array is zeroed once handed over. */
    private fun join(ssid: String, security: WifiSecurity, chars: CharArray) {
        val problem = passwordProblem(security, CharBuffer.wrap(chars))
        if (problem != null) {
            chars.fill('\u0000')
            status(this, problem)
            return
        }
        call("Connecting to $ssid...") {
            try {
                wifi.join(ssid, security, CharBuffer.wrap(chars))
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    private fun openSaved() {
        saved = SavedWifiScreen(screen.state.saved)
        render()
    }

    private fun tapSaved(i: Int) {
        saved = saved?.copy(focus = i)
        render()
    }

    private fun delete() {
        val n = saved?.focused ?: return
        Bold7Dialog(this, "Delete ${n.ssid}?", listOf("Delete", "Cancel"), DialogIcon.QUESTION, focused = 1, onButton = { b ->
            if (b == 0) call(null) { wifi.forget(n.id) }
        }).show()
    }

    /** [what] on io; [said] once it is accepted, "Not available" if Android refused. */
    private fun call(said: String?, what: () -> Boolean) {
        if (busy) return
        busy = true
        io.post {
            val ok = what()
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                if (!ok) status(this, "Not available") else if (said != null) status(this, said)
            }
        }
    }

    // Menu (guide p. 266: Saved Wi-Fi Networks' Menu > Delete, B; the rest D)

    private fun label(a: Act) = when (a) {
        Act.SWITCH -> "Switch Application"
        Act.CONNECT -> "Connect"
        Act.SCAN -> "Scan for Networks"
        Act.SAVED -> SAVED_TITLE
        Act.DELETE -> "Delete"
        Act.CLOSE -> "Close"
    }

    private fun show(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun fullMenu(): Menu<Act> {
        val list = saved
        val entries = buildList {
            if (list != null) {
                if (list.focused != null) {
                    add(MenuEntry(Act.CONNECT))
                    add(MenuEntry(Act.DELETE))
                }
            } else {
                val r = screen.rows.getOrNull(screen.focus)
                if (r is WifiRow.Network && !r.connected) add(MenuEntry(Act.CONNECT))
                add(MenuEntry(Act.SCAN))
                add(MenuEntry(Act.SAVED))
            }
            add(MenuEntry(Act.CLOSE, separatorAbove = isNotEmpty()))
        }
        return Menu(entries, highlighted = 0).withSwitchApplication(Act.SWITCH)
    }

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        when (a) {
            Act.SWITCH -> startSwitcher(this, fromHome = false)
            Act.CONNECT -> if (saved != null) saved?.focused?.let { n -> call("Connecting to ${n.ssid}...") { wifi.connect(n.id) } } else click()
            Act.SCAN -> call("Scanning for networks...") { wifi.scan() }
            Act.SAVED -> openSaved()
            Act.DELETE -> delete()
            Act.CLOSE -> back()
        }
    }

    private fun back() {
        if (saved != null) {
            saved = null
            render()
        } else {
            finish()
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        val handled = click || back || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_SPACE
        if (!handled) return super.dispatchKeyEvent(e)
        if (e.action != KeyEvent.ACTION_DOWN || (click && e.repeatCount > 0)) return true
        val m = menu
        if (m != null) {
            val key = when {
                code == KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                code == KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                click -> MenuKey.CLICK
                back -> MenuKey.BACK
                code == KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> return true
            }
            when (val r = m.key(key)) {
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> show(null)
            }
            return true
        }
        val list = saved
        when {
            code == KeyEvent.KEYCODE_MENU -> if (strips != null) show(fullMenu())
            back -> back()
            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                val down = code == KeyEvent.KEYCODE_DPAD_DOWN
                if (list != null) saved = list.step(down) else screen = screen.step(down)
                render()
            }
            // The click on a saved network opens its menu (D: the guide's Edit, Move, Delete are on the menu).
            click -> if (list != null) { if (list.focused != null) show(fullMenu()) } else click()
            code == KeyEvent.KEYCODE_SPACE -> if (list == null && screen.rows.getOrNull(screen.focus) is WifiRow.OpenOnly) click()
        }
        return true
    }

    companion object {
        /** Invented networks instead of Android's, for a capture (`am start` in root: the activity is not exported); nothing is written. */
        const val EXTRA_DEMO = "dev.bold7.options.DEMO"
        private const val TITLE = "Wi-Fi Network"
        private const val SAVED_TITLE = "Saved Wi-Fi Networks"
        private const val OPEN_ONLY = "Show Open networks only"
    }
}

/** The demonstration's networks (WifiActivity.EXTRA_DEMO), invented; every write refused. */
private object DemoWifi : WifiNetworks {
    override fun start(onChange: (WifiState) -> Unit) = onChange(
        WifiState(
            on = true,
            allowed = true,
            seen = listOf(
                WifiSeen("Home", 3, WifiSecurity.PERSONAL),
                WifiSeen("Cafe du Port", 2, WifiSecurity.OPEN),
                WifiSeen("Office", 3, WifiSecurity.ENTERPRISE),
                WifiSeen("Neighbour 5G", 1, WifiSecurity.WPA3),
                WifiSeen("Station Wi-Fi", 1, WifiSecurity.OWE),
            ),
            saved = listOf(WifiSaved(1, "Home", WifiSecurity.PERSONAL), WifiSaved(2, "Hotel Lobby", WifiSecurity.OPEN)),
            connected = "Home",
        ),
    )

    override fun stop() = Unit
    override fun scan() = true
    override fun join(ssid: String, security: WifiSecurity, password: CharSequence?) = false
    override fun connect(id: Int) = false
    override fun forget(id: Int) = false
}
