package dev.bold7.launcher.options

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.bold7.design.ListColors
import dev.bold7.launcher.LauncherActivity
import dev.bold7.launcher.graph
import dev.bold7.launcher.setup.TextSetupActivity
import dev.bold7.launcher.profiles.Profile
import dev.bold7.launcher.switcher.startSwitcher
import dev.bold7.platform.SystemDeviceFacts
import dev.bold7.platform.SystemDeviceSettings
import dev.bold7.platform.SystemZoneAndLanguage
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.CheckBoxView
import dev.bold7.ui.ChoiceListView
import dev.bold7.ui.ChoiceView
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.ListItemView
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.ScreenStack
import dev.bold7.ui.SearchField
import dev.bold7.ui.TitleBar
import dev.bold7.ui.status
import dev.bold7.ui.textPaint
import dev.bold7.design.Typography
import java.util.Locale

/**
 * Options of OS 7.1 (docs/BBOS7-VISUEL.md §17, §21, §27): the list of sim-57, its
 * lists, and the screens Bold7 draws (Trackpad Sensitivity, Typing, Screen Display, Phone
 * Ringtone); the other entries open Android's page. In the launcher's process, which already holds
 * WRITE_SECURE_SETTINGS (Manage Connections) and the sound profiles.
 * Renders the top of its [ScreenStack]; reads and writes go to [OptionsStore] on the io thread.
 * No status bar: OS 7's Options has its title instead (§18).
 *
 * Exported as every launcher activity; its only extra, [EXTRA_SCREEN], picks the screen it opens on;
 * nothing is written without the user's Save.
 */
class OptionsActivity : Activity() {
    private sealed interface Page {
        val screen: Screen
    }

    private data class ListPage(val listing: Listing) : Page {
        override val screen get() = listing.screen
    }

    private data class FormPage(override val screen: Screen, val form: Form) : Page

    private enum class Act { SWITCH, SAVE, CLOSE }

    private val graphIo get() = graph.io
    private val typingOptions by lazy { SaisieTypingOptions(applicationContext) }
    private val store by lazy { OptionsStore(SystemDeviceSettings(applicationContext), graph.profiles, graph.typingMode, typingOptions, SystemDeviceFacts(applicationContext), SystemZoneAndLanguage(applicationContext)) { inputLanguage() } }

    private var stack: ScreenStack<Page>? = null
    private var built: Any? = null
    private var busy = false
    private var menu: Menu<Act>? = null
    private val icons = HashMap<Int, Bitmap>()
    // The open list's text, to size it to its values (Time Zone, Display Language).
    private val listText = textPaint(Typography.TEXT, Typography.REGULAR, ListColors.ChoiceListText)

    private lateinit var title: TitleBar
    private lateinit var search: SearchField
    private lateinit var scroll: ScrollView
    private lateinit var rows: LinearLayout
    private lateinit var listView: ChoiceListView
    private lateinit var menuView: MenuView<Act>
    private var views: List<View> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        title = TitleBar(this)
        search = SearchField(this, "Search").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    val page = stack?.top as? ListPage ?: return
                    if (page.listing.query != s.toString()) replace(ListPage(page.listing.search(s.toString())))
                }
            })
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            // The focused scroll would draw the theme's 12 % focus highlight over the whole page; the activity draws its own focus.
            defaultFocusHighlightEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(rows, ViewGroup.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        listView = ChoiceListView(this, onChoose = { i -> formPage()?.let { replace(it.copy(form = it.form.choose(i))) } }, onCancel = {
            formPage()?.let { replace(it.copy(form = it.form.copy(open = null))) }
        })
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = ::chosen, onExpand = {}, onClose = { showMenu(null) })
        setContentView(FrameLayout(this).apply {
            addView(LinearLayout(this@OptionsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title, LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(search, LinearLayout.LayoutParams(match, SearchField.HEIGHT))
                addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(listView)
            addView(menuView)
        })
        // Without the bar's slide: OS 7 has no such bar.
        window.hideStatusBar()
        // The profile in use, read for Phone Ringtone's title and volume (it listens for good, once).
        graph.profiles.attach()
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        start(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.hide(WindowInsets.Type.statusBars())
    }

    /** The screen [EXTRA_SCREEN] names at the root (Change Sounds and Alerts; a test's capture), else Options' list. */
    private fun start(intent: Intent) {
        val screen = intent.getStringExtra(EXTRA_SCREEN)?.let { name -> Screen.entries.firstOrNull { it.name == name } } ?: Screen.MAIN
        stack = null
        showMenu(null)
        search.setText("")
        open(screen)
    }

    private fun formPage() = stack?.top as? FormPage

    /** Pushes [screen] once read off the main thread: a list's icons decoded, a form's values read. */
    private fun open(screen: Screen) {
        if (busy) return
        busy = true
        graphIo.post {
            val page: Page? = try {
                if (entries(screen).isEmpty()) FormPage(screen, store.form(screen))
                else ListPage(Listing(screen)).also { p -> p.listing.entries.forEach { e -> icon(e.icon) } }
            } catch (e: RuntimeException) {
                Log.e(TAG, "$screen not read", e)
                null
            }
            runOnUiThread {
                busy = false
                if (isDestroyed || page == null) return@runOnUiThread
                stack = stack?.push(page) ?: ScreenStack(page)
                render()
            }
        }
    }

    // Decoded on io, kept for the activity; read on the main thread once there.
    private fun icon(id: Int): Bitmap? = synchronized(icons) { icons[id] } ?: BitmapFactory.decodeResource(resources, id)?.also { synchronized(icons) { icons[id] = it } }

    private fun replace(page: Page) {
        val s = stack ?: return
        stack = ScreenStack(s.screens.dropLast(1) + page)
        render()
    }

    private fun pop() {
        val s = stack?.pop() ?: return finish()
        stack = s
        render()
    }

    private fun render() {
        val page = stack?.top ?: return
        val key: Any = when (page) {
            is ListPage -> page.screen to page.listing.query
            is FormPage -> page.screen
        }
        if (key != built) build(page)
        built = key
        title.bind(if (page is FormPage) page.form.title else title(page.screen))
        when (page) {
            is ListPage -> page.listing.shown.forEachIndexed { i, e ->
                (views[i] as ListItemView).bind(e.title, e.subtitle, lit = i == page.listing.focus, icon = synchronized(icons) { icons[e.icon] })
            }
            is FormPage -> {
                val f = page.form
                f.fields.forEachIndexed { i, field ->
                    val lit = i == f.focus && f.open == null
                    when (field) {
                        is Choice -> (views[i] as ChoiceView).bind(field.label, field.values.getOrElse(field.chosen) { "" }, lit)
                        is Check -> (views[i] as CheckBoxView).bind(field.label, field.checked, lit, field.enabled)
                        is Note -> (views[i] as NoteView).bind(field.label, field.note, lit, field.red)
                        is Button -> (views[i] as ButtonRowView).bind(field.label, lit, field.enabled)
                    }
                }
                showList(f)
            }
        }
        val focus = when (page) {
            is ListPage -> page.listing.focus
            is FormPage -> page.form.focus
        }
        views.getOrNull(focus)?.let { v -> v.post { v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), true) } }
    }

    private fun build(page: Page) {
        rows.removeAllViews()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        when (page) {
            is ListPage -> {
                search.visibility = if (page.screen == Screen.MAIN) View.VISIBLE else View.GONE
                if (page.screen == Screen.MAIN) search.requestFocus()
                rows.setBackgroundColor(ListColors.Background)
                scroll.setBackgroundColor(ListColors.Background)
                views = page.listing.shown.indices.map { i ->
                    ListItemView(this).apply { setOnClickListener { listTap(i) } }.also { rows.addView(it, LinearLayout.LayoutParams(match, ListItemView.HEIGHT)) }
                }
            }
            is FormPage -> {
                search.visibility = View.GONE
                rows.setBackgroundColor(ListColors.GroupBackground)
                scroll.setBackgroundColor(ListColors.GroupBackground)
                val all = ArrayList<View>()
                for (g in page.form.groups) {
                    val box = GroupBox(this, g.title)
                    for (field in g.fields) {
                        val i = all.size
                        val v: View = when (field) {
                            is Choice -> ChoiceView(this)
                            is Check -> CheckBoxView(this)
                            is Note -> NoteView(this)
                            is Button -> ButtonRowView(this)
                        }
                        v.setOnClickListener { formResult(formPage()?.form?.tap(i)) }
                        val h = when (field) {
                            is Choice -> ChoiceView.HEIGHT
                            is Check -> CheckBoxView.HEIGHT
                            else -> wrap
                        }
                        box.addView(v, LinearLayout.LayoutParams(match, h))
                        all += v
                    }
                    rows.addView(box, LinearLayout.LayoutParams(match, wrap))
                }
                views = all
            }
        }
        scroll.scrollTo(0, 0)
    }

    // The open list of the focused choice, placed at its field (ChoiceList.top), or none.
    private fun showList(f: Form) {
        val open = f.open
        val c = f.fields.getOrNull(f.focus) as? Choice
        if (open == null || c == null) return listView.show(null)
        if (listView.visibility == View.VISIBLE) return listView.show(open)
        val row = views[f.focus] as ChoiceView
        val at = IntArray(2).also { row.getLocationInWindow(it) }
        val origin = IntArray(2).also { listView.getLocationInWindow(it) }
        val widest = c.values.maxOfOrNull(listText::measureText) ?: 0f
        val (width, left) = FormRoom.choiceList(widest, row.fieldLeft, row.width)
        listView.open(c.values, open, at[0] - origin[0] + left, at[1] - origin[1], c.unavailable, width)
    }

    private fun listTap(row: Int) {
        val page = stack?.top as? ListPage ?: return
        replace(ListPage(page.listing.copy(focus = row)))
        page.listing.target(row)?.let(::go)
    }

    private fun go(target: Target) = when (target) {
        is Target.Open -> open(target.screen)
        is Target.Android -> android(target.action)
        Target.Substitutions -> startActivity(Intent(this, WordSubstitutionActivity::class.java))
        Target.Wifi -> startActivity(Intent(this, WifiActivity::class.java))
        Target.TextSetup -> startActivity(Intent(this, TextSetupActivity::class.java))
        // The home, in front, shows Manage Connections (LauncherActivity.onNewIntent).
        Target.Connections -> startActivity(Intent(this, LauncherActivity::class.java).putExtra(EXTRA_MANAGE_CONNECTIONS, true))
    }

    private fun android(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no page for $action", e)
            status(this, "Not available")
        }
    }

    private fun formResult(r: FormResult?) {
        val page = formPage() ?: return
        when (r) {
            null -> Unit
            is FormResult.Show -> replace(page.copy(form = r.form))
            is FormResult.Act -> when {
                // Input Language: Options' Language, which sets the phone's.
                page.screen == Screen.TYPING && page.form.fields[r.field] is Note -> open(Screen.LANGUAGE)
                page.screen == Screen.PROFILES -> selectProfile(page, r.field)
                page.screen == Screen.TYPING -> open(Screen.ADVANCED_STYLE)
                page.screen == Screen.ADVANCED_STYLE -> forgetLearned()
                page.screen == Screen.HOME -> resetHome()
            }
            FormResult.Leave -> pop()
            // "Changes made!" (sim-47, C): Save, Discard, Cancel.
            FormResult.AskSave -> Bold7Dialog(this, "Changes made!", listOf("Save", "Discard", "Cancel"), DialogIcon.QUESTION, onButton = {
                when (it) {
                    0 -> save()
                    1 -> pop()
                }
            }).show()
        }
    }

    /** Profile Management: the profile at [field] becomes the one in use, shown at once (Android's state follows on io). */
    private fun selectProfile(page: FormPage, field: Int) {
        val profile = Profile.entries.getOrNull(field) ?: return
        graph.profiles.select(profile)
        replace(page.copy(form = store.profilesForm(profile).copy(focus = field)))
    }

    /** Reset to Default Settings (sim-284, C): what Home Screen Preferences holds goes back to its default, once asked (D). */
    private fun resetHome() {
        Bold7Dialog(this, RESET_HOME_QUESTION, listOf("Reset", "Cancel"), DialogIcon.QUESTION, focused = 1, onButton = { b ->
            if (b != 0 || busy) return@Bold7Dialog
            busy = true
            graphIo.post {
                val form = try {
                    store.resetHome()
                    store.form(Screen.HOME)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "home not reset", e)
                    null
                }
                runOnUiThread {
                    busy = false
                    if (isDestroyed) return@runOnUiThread
                    if (form == null) status(this, "Not available") else (formPage()?.takeIf { it.screen == Screen.HOME })?.let { replace(it.copy(form = form)) }
                }
            }
        }).show()
    }

    /** Delete Learned Words (D): asked first, then at once, without the form's Save. */
    private fun forgetLearned() {
        Bold7Dialog(this, DELETE_LEARNED_QUESTION, listOf("Delete", "Cancel"), DialogIcon.QUESTION, focused = 1, onButton = { b ->
            if (b != 0 || busy) return@Bold7Dialog
            busy = true
            graphIo.post {
                val ok = typingOptions.forget()
                runOnUiThread {
                    busy = false
                    if (!isDestroyed && !ok) status(this, "Not available")
                }
            }
        }).show()
    }

    private fun save() {
        val page = formPage() ?: return
        if (busy) return
        busy = true
        graphIo.post {
            val ok = try {
                store.save(page.screen, page.form)
            } catch (e: RuntimeException) {
                Log.e(TAG, "${page.screen} not saved", e)
                false
            }
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                if (ok) pop() else status(this, "Changes not saved")
            }
        }
    }

    private fun label(act: Act) = when (act) {
        Act.SWITCH -> "Switch Application"
        Act.SAVE -> "Save"
        Act.CLOSE -> "Close"
    }

    private fun showMenu(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val act = menu?.entries?.getOrNull(index)?.action ?: return
        showMenu(null)
        when (act) {
            Act.SWITCH -> startSwitcher(this, fromHome = false)
            Act.SAVE -> save()
            Act.CLOSE -> if (formPage() != null) formResult(formPage()?.form?.key(FormKey.BACK)) else pop()
        }
    }

    // The full menu of an options screen (guide 7.1, "Press the Menu key > Save", B): Switch
    // Application, Save, Close, Save under the focus; a list's has no Save (D).
    private fun fullMenu(): Menu<Act> = if (formPage() != null) {
        Menu(listOf(MenuEntry(Act.SWITCH), MenuEntry(Act.SAVE, separatorAbove = true), MenuEntry(Act.CLOSE)), highlighted = 1)
    } else {
        Menu(listOf(MenuEntry(Act.SWITCH), MenuEntry(Act.CLOSE, separatorAbove = true)), highlighted = 1)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        val handled = click || back || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            (code == KeyEvent.KEYCODE_SPACE && formPage() != null)
        if (!handled) return super.dispatchKeyEvent(e)
        if (e.action != KeyEvent.ACTION_DOWN || (click && e.repeatCount > 0)) return true
        if (busy) return true
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
                is MenuResult.Show -> showMenu(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> showMenu(null)
            }
            return true
        }
        when (val page = stack?.top) {
            null -> if (back) finish()
            is ListPage -> when {
                code == KeyEvent.KEYCODE_MENU -> showMenu(fullMenu())
                code == KeyEvent.KEYCODE_DPAD_UP -> replace(ListPage(page.listing.step(down = false)))
                code == KeyEvent.KEYCODE_DPAD_DOWN -> replace(ListPage(page.listing.step(down = true)))
                click -> page.listing.target()?.let(::go)
                // Escape empties the search first (D), then goes back.
                back -> if (page.listing.query.isNotEmpty()) search.setText("") else pop()
            }
            is FormPage -> {
                if (code == KeyEvent.KEYCODE_MENU) {
                    if (page.form.open == null) showMenu(fullMenu())
                    return true
                }
                val key = when {
                    code == KeyEvent.KEYCODE_DPAD_UP -> FormKey.UP
                    code == KeyEvent.KEYCODE_DPAD_DOWN -> FormKey.DOWN
                    click -> FormKey.CLICK
                    back -> FormKey.BACK
                    else -> FormKey.SPACE
                }
                formResult(page.form.key(key))
            }
        }
        return true
    }

    companion object {
        private const val TAG = "Bold7/Options"

        /** The screen to open at the root ([Screen] name); the launcher's Change Sounds and Alerts gives SOUNDS. */
        const val EXTRA_SCREEN = "dev.bold7.options.SCREEN"

        /** Opens Options, at [screen] when given. */
        fun start(context: Context, screen: Screen = Screen.MAIN) {
            context.startActivity(
                Intent(context, OptionsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_SCREEN, screen.name),
            )
        }

        /** The input language as Typing shows it, "English(UK)" (sim-91a, C): the phone's language, D. */
        fun inputLanguage(): String {
            val l = Locale.getDefault()
            val language = l.getDisplayLanguage(Locale.ENGLISH)
            return if (l.country.isEmpty()) language else "$language(${l.country})"
        }
    }
}
