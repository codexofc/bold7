package dev.bold7.launcher.setup

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.bold7.design.ListColors
import dev.bold7.design.Typography
import dev.bold7.launcher.R
import dev.bold7.launcher.graph
import dev.bold7.launcher.options.ButtonRowView
import dev.bold7.launcher.options.GroupBox
import dev.bold7.launcher.options.NoteView
import dev.bold7.launcher.options.OptionsActivity
import dev.bold7.launcher.options.Screen
import dev.bold7.launcher.profiles.Profile
import dev.bold7.launcher.profiles.ProfilePopup
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.CheckBoxView
import dev.bold7.ui.Dir
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.ListItemView
import dev.bold7.ui.TitleBar
import dev.bold7.ui.themeBitmap
import java.io.File

/**
 * The Setup Wizard (Wizard.kt): the pages of OS 7's Setup application (sim-385, sim-389, C), a
 * title bar, group boxes on grey, a grey band with the buttons at the foot; one page at a time,
 * Back and Next. Opened once by the home at the first unlock, then by the Setup icon.
 *
 * Extras, for tests and captures: [EXTRA_PAGE] opens on that page (a [Page] name, or "missing");
 * [EXTRA_DRY_RUN] writes nothing (no setting, no wallpaper, no profile, no page of Google or
 * Android opened), only says in the log what it would do.
 */
class WizardActivity : Activity() {
    private val dryRun by lazy { intent.getBooleanExtra(EXTRA_DRY_RUN, false) }
    private val device by lazy { WizardDevice(applicationContext, dryRun) }
    private var state = State()
    private var built: Any? = null
    private var waitingForTexts = false
    private var wallpapers = emptyList<File>()

    private lateinit var title: WizardTitle
    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    private lateinit var band: BandView
    private val binders = mutableListOf<(State) -> Unit>()
    private val views = HashMap<Control, View>()
    private var grid: WallpaperGridView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.hideStatusBar()
        val page = intent.getStringExtra(EXTRA_PAGE)
        if (page == MISSING || device.assetsMissing()) return showMissing()
        title = WizardTitle(this)
        scroll = ScrollView(this).apply {
            setBackgroundColor(ListColors.GroupBackground)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // The focus is the wizard's own (Wizard.kt): Android's would grey the whole page after a trackpad key.
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            defaultFocusHighlightEnabled = false
        }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        band = BandView(this, plain = false) { i -> dispatch(Event.Click(band(state.page)[i])) }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ListColors.GroupBackground)
                addView(title, ViewGroup.LayoutParams.MATCH_PARENT, TitleBar.HEIGHT)
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(band, ViewGroup.LayoutParams.MATCH_PARENT, BandView.HEIGHT)
            },
        )
        val start = Page.entries.firstOrNull { it.name.equals(page, ignoreCase = true) } ?: Page.WELCOME
        state = State(page = start).let { it.copy(focus = defaultFocus(it)) }
        render()
        graph.profiles.attach()
        graph.io.post {
            // Shown once: marked at once, so that it never comes back on its own, whatever happens next.
            device.markDone()
            val installed = device.messagesInstalled()
            val consent = if (installed) device.consent() else Consent.UNKNOWN
            val files = device.wallpapers()
            val profile = getString(profileName(graph.profiles.current))
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                wallpapers = files
                dispatch(Event.Messages(installed))
                dispatch(Event.ConsentRead(consent, back = false))
                dispatch(Event.ProfileSet(profile))
                dispatch(Event.Wallpapers(files.size))
            }
        }
    }

    // Opened again while it runs (the Setup icon, a test): the page asked for, else where it was.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (missing) return
        val page = Page.entries.firstOrNull { it.name.equals(intent.getStringExtra(EXTRA_PAGE), ignoreCase = true) } ?: return
        state = state.copy(page = page).let { it.copy(focus = defaultFocus(it)) }
        render()
    }

    // Back from Google's page: its switch read again, off the main thread.
    override fun onResume() {
        super.onResume()
        if (!waitingForTexts) return
        waitingForTexts = false
        readConsentBack()
    }

    private fun readConsentBack() = graph.io.post {
        val consent = device.consent()
        runOnUiThread { if (!isDestroyed) dispatch(Event.ConsentRead(consent, back = true)) }
    }

    private fun dispatch(e: Event) {
        val r = reduce(state, e)
        state = r.state
        render()
        r.effect?.let(::act)
    }

    private fun act(effect: Effect) = when (effect) {
        Effect.Finish -> {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
        Effect.OpenTexts -> device.openTexts(this) { opened -> if (opened) waitingForTexts = true else readConsentBack() }
        Effect.PickProfile -> ProfilePopup(
            this, graph.profiles.current,
            onProfile = { p ->
                if (dryRun) Log.i(TAG, "dry run: profile ${p.id} not set") else graph.profiles.select(p)
                dispatch(Event.ProfileSet(getString(profileName(p))))
            },
            onSettings = { OptionsActivity.start(this, Screen.SOUNDS) },
            onCancel = {},
        ).show()
        Effect.ConfirmDefaultApps -> ask(CHANGES_CONFIRM, listOf("Open", "Cancel")) {
            device.openDefaultApps(this)
            dispatch(Event.Advance)
        }
        is Effect.ConfirmWallpaper -> wallpapers.getOrNull(effect.index)?.let { file ->
            ask("Use ${file.nameWithoutExtension} as your wallpaper, on the home screen and the lock screen?", listOf("Set", "Cancel")) {
                graph.io.post {
                    val set = device.setWallpaper(file)
                    runOnUiThread { if (set && !isDestroyed) dispatch(Event.WallpaperSet(effect.index)) }
                }
            }
        }
    }

    private fun ask(text: String, buttons: List<String>, then: () -> Unit) =
        Bold7Dialog(this, text, buttons, DialogIcon.QUESTION, onButton = { if (it == 0) then() }, hideStatusBar = true).show()

    // --- drawing ---

    private fun render() {
        val key = Triple(state.page, state.messagesInstalled, state.wallpapers)
        if (key != built) {
            built = key
            build()
        }
        title.bind(state.page.title, progress(state.page))
        val f = focused(state)
        band.bind(band(state.page).map { if (it == Control.Next) nextLabel(state) else "Back" }, if (f in band(state.page)) band(state.page).indexOf(f) else -1)
        binders.forEach { it(state) }
        reveal(f)
    }

    // The focused control brought into view; the band is always in view.
    private fun reveal(c: Control) {
        if (c is Control.Wallpaper) grid?.let { it.post { it.requestRectangleOnScreen(it.cellRect(c.index), false) } }
        else views[c]?.let { v -> v.post { v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), false) } }
    }

    private fun build() {
        column.removeAllViews()
        binders.clear()
        views.clear()
        grid = null
        when (state.page) {
            Page.WELCOME -> box("Welcome to Bold7") {
                text(WELCOME_TITLE, bold = true)
                text(WELCOME_TEXT)
                add(ImageRowView(context, "bbos7_setup"))
                text(WELCOME_NEXT)
            }
            Page.CHANGES -> {
                box("Bold7 Has Set Up") { CHANGES_DONE.forEach { add(ParagraphView(context, it, badge = true)) } }
                box("Keep Bold7") {
                    check(Control.KeepHome, CHANGES_KEEP_HOME) { it.keepHome }
                    check(Control.KeepPhone, CHANGES_KEEP_PHONE) { it.keepPhone }
                }
                box("To Undo") { text(CHANGES_UNDO) }
            }
            Page.TEXTS -> box(Page.TEXTS.title) {
                if (!state.messagesInstalled) return@box text(TEXTS_ABSENT)
                text(TEXTS_WHY)
                add(ImageRowView(context, "bbos7_text_messages"))
                text(TEXTS_HOW)
                val button = ButtonRowView(context)
                control(Control.OpenTexts, button) { button.bind(TEXTS_OPEN, it, enabled = true) }
                val done = ParagraphView(context, "", badge = true)
                val notYet = ParagraphView(context, "")
                add(done)
                add(notYet)
                binders += { s ->
                    val line = consentLine(s).orEmpty()
                    done.bind(if (s.consent == Consent.ON) line else "")
                    notYet.bind(if (s.consent == Consent.ON) "" else line)
                }
                text(TEXTS_SKIP, size = Typography.SUBTITLE, color = ListColors.Subtitle)
            }
            Page.LOOK -> {
                box(LOOK_PROFILE) {
                    text(LOOK_PROFILE_TEXT)
                    val row = ListItemView(context)
                    val icon = themeBitmap(context, "opt_sound")
                    control(Control.Profile, row, ListItemView.HEIGHT) { lit -> row.bind("$LOOK_PROFILE: ${state.profile}", LOOK_PROFILE_NOTE, lit, icon) }
                }
                if (state.wallpapers > 0) box(LOOK_WALLPAPER) {
                    text(LOOK_WALLPAPER_TEXT)
                    val g = WallpaperGridView(context, state.wallpapers) { dispatch(Event.Click(Control.Wallpaper(it))) }
                    add(g)
                    grid = g
                    binders += { s ->
                        g.lit = (focused(s) as? Control.Wallpaper)?.index ?: -1
                        g.chosen = s.chosenWallpaper ?: -1
                    }
                    loadThumbnails(g)
                }
            }
            Page.DONE -> box(Page.DONE.title) {
                text(DONE_TEXT)
                for ((key, what) in DONE_KEYS) add(NoteView(context).apply { bind(key, what, lit = false) })
                text(DONE_AGAIN, size = Typography.SUBTITLE, color = ListColors.Subtitle)
            }
        }
        scroll.scrollTo(0, 0)
    }

    private fun loadThumbnails(g: WallpaperGridView) {
        val files = wallpapers
        g.post {
            val side = g.side()
            if (side <= 0) return@post
            graph.io.post {
                files.forEachIndexed { i, f ->
                    val t = runCatching { device.thumbnail(f, side) }.getOrNull()
                    runOnUiThread { if (i < g.thumbs.size) { g.thumbs[i] = t; g.invalidate() } }
                }
            }
        }
    }

    private class Box(val activity: WizardActivity, val group: GroupBox) {
        val context get() = activity

        fun add(v: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = group.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, height)

        fun text(t: String, bold: Boolean = false, size: Int = Typography.TEXT, color: Int = ListColors.Title) =
            add(ParagraphView(activity, t, size, bold, color))

        fun control(c: Control, v: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, bind: (Boolean) -> Unit) {
            add(v, height)
            v.setOnClickListener { activity.dispatch(Event.Click(c)) }
            activity.views[c] = v
            activity.binders += { s -> bind(focused(s) == c) }
        }

        fun check(c: Control, label: String, checked: (State) -> Boolean) {
            val v = CheckBoxView(activity)
            add(v, CheckBoxView.HEIGHT)
            v.setOnClickListener { activity.dispatch(Event.Click(c)) }
            activity.views[c] = v
            activity.binders += { s -> v.bind(label, checked(s), lit = focused(s) == c) }
        }
    }

    private fun box(name: String, fill: Box.() -> Unit) {
        val g = GroupBox(this, name)
        Box(this, g).fill()
        column.addView(g, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Without the 9900's pictures: a page drawn with none of them, which says what to do (D). */
    private fun showMissing() {
        val missingTitle = TitleBar(this, "Setup")
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
            for (t in listOf(MISSING_TEXT, MISSING_HOW, MISSING_THEN, CHANGES_UNDO)) {
                addView(ParagraphView(this@WizardActivity, t), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        val ok = BandView(this, plain = true) { finish() }.apply { bind(listOf("OK"), 0) }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ListColors.Background)
                addView(missingTitle, ViewGroup.LayoutParams.MATCH_PARENT, TitleBar.HEIGHT)
                addView(ScrollView(this@WizardActivity).apply { addView(texts) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(ok, ViewGroup.LayoutParams.MATCH_PARENT, BandView.HEIGHT)
            },
        )
        missing = true
    }

    private var missing = false

    // --- keys: the trackpad's arrows, its click, Enter and Space, Back and Escape ---

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Dir.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
            else -> null
        }
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        if (dir == null && !click && !back) return super.dispatchKeyEvent(e)
        if (e.action != KeyEvent.ACTION_UP) return true
        when {
            missing -> if (click || back) finish()
            dir != null -> dispatch(Event.Move(dir))
            click -> dispatch(Event.Click(focused(state)))
            // Escape goes back a page; on the first, it closes: everything is already in place.
            back -> if (state.page == Page.WELCOME) finish() else dispatch(Event.Click(Control.Back))
        }
        return true
    }

    companion object {
        private const val TAG = "Bold7Setup"
        const val EXTRA_PAGE = "dev.bold7.setup.PAGE"
        const val EXTRA_DRY_RUN = "dev.bold7.setup.DRY_RUN"
        private const val MISSING = "missing"

        /** The name of a profile, as Select a Profile shows it. */
        fun profileName(p: Profile) = when (p) {
            Profile.NORMAL -> R.string.profile_normal
            Profile.VIBRATE -> R.string.profile_vibrate
            Profile.SILENT -> R.string.profile_silent
            Profile.LOUD -> R.string.profile_loud
            Profile.PHONE_ONLY -> R.string.profile_phone_only
            Profile.ALERTS_OFF -> R.string.profile_alerts_off
            Profile.MEDIUM -> R.string.profile_medium
        }
    }
}
