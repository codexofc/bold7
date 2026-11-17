package dev.bold7.launcher.keys

import android.content.Context
import android.content.Intent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import dev.bold7.design.HomeDimens
import dev.bold7.launcher.home.BannerFocus
import dev.bold7.launcher.home.HomeEvent
import dev.bold7.launcher.home.HomeState
import dev.bold7.launcher.home.HomeView
import dev.bold7.ui.Dir
import dev.bold7.ui.LetterHold
import dev.bold7.ui.SpeedDialKey
import dev.bold7.ui.MenuKey
import dev.bold7.ui.Travel

/**
 * The Q25's keyboard, Call key and trackpad on the BBOS 7 home: the Android side of Focus.kt and
 * Typing.kt. LauncherActivity hands it every key and the trackpad's captured movements; it
 * decides through the pure functions and does what they say; [home] draws the focus.
 */
internal class HomeKeys(
    private val context: Context,
    private val home: HomeView,
    private val homeState: () -> HomeState,
    private val dispatch: (HomeEvent) -> Unit,
    /** Opens the search with what was typed: over the home, in its window. */
    private val search: (String) -> Unit,
    /** A banner target clicked (the magnifier's search, the others' lots to come). */
    private val banner: (BannerFocus) -> Unit,
    /** Launch by Typing, as saved (guide 7.1 p. 24). */
    private val mode: () -> LaunchByTyping,
) {
    /** The list of notifications while it is open: it takes the trackpad first. */
    var list: HomeList? = null

    /** Holds Android's focus to get the trackpad's moves; draws nothing. */
    val view = KeyTarget(context, ::onPointer)

    private var typing = Typing()

    // A first letter waits for its up, or its long press: held, it calls its speed dial (guide 7.1 p. 65).
    private val letters = LetterHold()
    private var focus: Focus? = null
    private var travel = Travel()
    private var held = false // the trackpad (or Enter) is down
    private var clicking = false // ...on a focus shown before it went down, not moved since

    init {
        // A view that took Android's focus gives it back, unless it is a text being typed.
        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> if (now != null && now !is EditText && now !== view) view.requestFocus() }
    }

    /** True when the key is the home's: everything else goes on to Android (volume, a text being typed). */
    fun onKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        // A letter waiting for its up acts as a tap before any other key.
        if (e.keyCode != letterKey) letters.flush()?.let { letter(it) }
        if (home.menuOpen) return menuKey(e, down)
        when (e.keyCode) {
            // The full menu (sim-04), with the focused icon's actions, on the key's up. The Menu
            // key's long press never reaches here as KEYCODE_MENU: the module's key daemon
            // sends a short press as KEY_MENU and a long one as KEY_APPSELECT, which
            // SystemUI hands to the QuickstepService: that opens the app switcher.
            KeyEvent.KEYCODE_MENU -> if (!down && !e.isCanceled) home.fullMenu((focus as? Focus.Icon)?.index)
            KeyEvent.KEYCODE_DPAD_UP -> if (down) step(Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) step(Dir.DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) step(Dir.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) step(Dir.RIGHT)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                down -> if (e.repeatCount == 0) press()
                e.isCanceled -> cancelClick()
                else -> release()
            }
            // End on the home changes nothing: the tray stays raised, the focus where it is
            // (sim-78, C). It never reaches here: PhoneWindowManager keeps ENDCALL from
            // every app, and its goHome() starts the home with START_FLAG_ONLY_IF_NEEDED, which
            // delivers nothing when the home is already in front. Kept should that ever change.
            KeyEvent.KEYCODE_ENDCALL -> Unit
            // The Call key opens the phone (guide 7.1, M). Taken whole: Android would start its
            // voice dialer on a long press.
            KeyEvent.KEYCODE_CALL -> if (!down && !e.isCanceled) start(Intent(Intent.ACTION_DIAL))
            else -> return typed(e)
        }
        return true
    }

    /**
     * An open menu takes the keys, through core/ui's reducer (Menu.key): the trackpad moves its
     * highlight, the click chooses, Escape closes, Menu closes the full menu or expands the short one.
     */
    private fun menuKey(e: KeyEvent, down: Boolean): Boolean {
        val key = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> menuKeyOf(Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> menuKeyOf(Dir.DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> menuKeyOf(Dir.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> menuKeyOf(Dir.RIGHT)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> MenuKey.CLICK
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
            KeyEvent.KEYCODE_MENU -> MenuKey.MENU
            else -> return false
        }
        // The up of the Enter whose hold opened the short menu is not a click of it.
        val opener = held && key == MenuKey.CLICK
        if (!down || key != MenuKey.CLICK) cancelClick()
        // Moves on their down, the others on their up (the down that opened the menu is not theirs).
        val moves = key == MenuKey.UP || key == MenuKey.DOWN || key == MenuKey.LEFT || key == MenuKey.RIGHT
        if (!opener && down == moves && !e.isCanceled) home.menuKey(key)
        return true
    }

    /** Letters and signs: the search with them, or the app of the letter, as Launch by Typing says. */
    private fun typed(e: KeyEvent): Boolean {
        val char = e.getUnicodeChar(e.metaState)
        if (char == 0 || char and KeyCharacterMap.COMBINING_ACCENT != 0 || Character.isWhitespace(char) || Character.isISOControl(char)) return false
        val c = char.toChar()
        // The first letter, before the search opens: a tap on its up, a hold its speed dial.
        if (!typing.searching && c.lowercaseChar() in 'a'..'z') {
            if (e.action == KeyEvent.ACTION_UP) letters.up(e.keyCode)?.let { letter(it) }
            else {
                letterKey = e.keyCode
                letters.down(e.keyCode, c, e.repeatCount).forEach(::letter)
            }
            return true
        }
        // A key held types once: the repeats and the ups are the first down's.
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return true
        typeLetter(c)
        return true
    }

    private var letterKey = -1 // the key code of the letter last handed to [letters]

    private fun letter(out: LetterHold.Out) = when (out) {
        is LetterHold.Out.Tap -> typeLetter(out.letter)
        is LetterHold.Out.Hold -> SpeedDialKey.call(context, out.letter)
    }

    private fun typeLetter(c: Char) {
        val (next, effect) = type(typing, mode(), TypingEvent.Down(c))
        typing = next
        when (effect) {
            is TypingEffect.Search -> search(effect.text)
            is TypingEffect.Open -> open(effect.letter)
            TypingEffect.Help -> dev.bold7.ui.Help.open(context)
            null -> Unit
        }
    }

    private fun open(letter: Char) {
        val apps = homeState().apps
        val app = appForLetter(letter, apps.map { it.label })?.let(apps::get) ?: return
        start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(app.packageName, app.className))
    }

    /** The trackpad as a relative pointer, captured while the home has the window focus. */
    private fun onPointer(e: MotionEvent): Boolean {
        // An open menu takes the trackpad: its steps are the menu's arrows, a click pressed over
        // it chooses on release; nothing reaches the home behind, no hold is armed. The release
        // of the press whose hold opened the menu is not a click of it.
        if (home.menuOpen) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> if (!held) menuPressed = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    if (menuPressed) home.menuKey(MenuKey.CLICK)
                    menuPressed = false
                    cancelClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    menuPressed = false
                    cancelClick()
                }
                MotionEvent.ACTION_MOVE -> moved(e).forEach { home.menuKey(menuKeyOf(it)) }
            }
            return true
        }
        menuPressed = false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> press()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> release()
            MotionEvent.ACTION_CANCEL -> cancelClick()
            MotionEvent.ACTION_MOVE -> moved(e).forEach(::step)
        }
        return true
    }

    private var menuPressed = false // a trackpad press that went down over an open menu

    /** The trackpad's steps in a captured move: each sample is a move from the one before. */
    private fun moved(e: MotionEvent): List<Dir> {
        var dx = e.x
        var dy = e.y
        for (i in 0 until e.historySize) {
            dx += e.getHistoricalX(i)
            dy += e.getHistoricalY(i)
        }
        val (next, steps) = travel.moved(dx, dy)
        travel = next
        return steps
    }

    // The click acts on release; a click with no focus shows it. A captured click comes as a down
    // and a button press: the first one counts. Held on an icon, it opens its short menu (sim-17,
    // "trackpad maintenu"; the delay is Android's long press, D).
    private fun press() {
        if (held) return
        held = true
        if (focus == null) {
            // Over the open list, the row's zone (sim-26, C), never the hidden tray.
            show(if (list?.isOpen == true) Focus.Top(BannerFocus.NOTIFICATIONS) else move(null, Dir.DOWN, count()).focus)
            return
        }
        clicking = true
        if (focus is Focus.Icon && homeState().moving == null) view.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private val hold = Runnable {
        if (!clicking) return@Runnable
        clicking = false
        (focus as? Focus.Icon)?.let { home.shortMenu(it.index) }
    }

    private fun release() {
        if (!held) return
        held = false
        view.removeCallbacks(hold)
        if (!clicking) return
        clicking = false
        // Moving, the click drops the icon where it is (sim-17's Move, D).
        if (homeState().moving != null) {
            dispatch(HomeEvent.EndMove)
            return
        }
        if (list?.click() == true) return
        when (val f = focus?.let { valid(it, count()) }) {
            is Focus.Icon -> home.open(f.index)
            // A click on the tab raises or lowers the tray (D, as a tap does).
            Focus.Tab -> dispatch(HomeEvent.Expand(!homeState().expanded))
            is Focus.Top -> banner(f.target)
            null -> Unit
        }
    }

    /** No click any more: canceled, the window gone, or the focus moved while pressed. */
    private fun cancelClick() {
        held = false
        clicking = false
        view.removeCallbacks(hold)
    }

    private fun count() = homeState().shown.size

    private fun step(dir: Dir) {
        cancelClick()
        // The open list's lines under the row's zone (sim-100 to sim-103, C): from the zone, the
        // speaker or the magnifier, never from the banner, which goes down to the zone first.
        val l = list
        if (l != null && l.isOpen) {
            // The tray is hidden: the focus stays above it, on the row or in the list.
            if (focus !is Focus.Top) focus = Focus.Top(BannerFocus.NOTIFICATIONS)
            if (focus != Focus.Top(BannerFocus.BANNER) && l.step(dir)) {
                focus = Focus.Top(BannerFocus.NOTIFICATIONS)
                refocus()
                return
            }
        }
        val expanded = homeState().expanded
        // Moving: the icon goes where the trackpad takes it, never off the grid.
        homeState().moving?.let { at ->
            val m = move(Focus.Icon(at), dir, count(), expanded)
            if (m.expand) dispatch(HomeEvent.Expand(true))
            val to = m.focus as? Focus.Icon ?: return
            if (to.index != at) dispatch(HomeEvent.MoveTo(to.index))
            show(to)
            return
        }
        val m = move(focus, dir, count(), expanded, lastIcon)
        if (m.panel != 0) {
            lastIcon = 0 // another view: its first icon (D)
            dispatch(HomeEvent.ShowPanel(homeState().let { it.panel.next(m.panel, it.hiddenPanels) }))
        }
        // Down from the lowered row: the tray rises (its 250 ms, as Maximise Panel), the focus stays.
        if (m.expand) dispatch(HomeEvent.Expand(true))
        show(m.focus)
    }

    private var lastIcon = 0 // the icon focused last: down from the tab goes back to it

    private fun show(next: Focus?) {
        focus = next
        if (next is Focus.Icon) lastIcon = next.index
        home.showFocus(next)
    }

    private var wasExpanded = false

    /** After a new state: the focus on a tile gone moves to the last one; the tray lowered, to its column in the first row. */
    fun stateChanged() {
        val expanded = homeState().expanded
        val lowering = wasExpanded && !expanded
        wasExpanded = expanded
        if (lowering) lastIcon %= HomeDimens.COLUMNS
        val f = focus ?: return
        val v = valid(if (lowering) lowered(f) else f, count())
        if (v != f) show(v)
    }

    /** The focus drawn again: on the row's zone, or on nothing while a line of the open list has it. */
    fun refocus() = home.showFocus(if (list?.lineFocused == true) null else focus)

    /** A touch on the screen hides the focus. */
    fun clearFocus() {
        cancelClick()
        list?.clearLineFocus()
        if (focus == null) return
        travel = Travel()
        show(null)
    }

    /** The home key, or the home gone: nothing typed, nothing focused. */
    fun reset() {
        typing = Typing()
        letters.clear()
        clearFocus()
    }

    /**
     * The window's focus: when it comes, the trackpad is asked for (Android releases it when it
     * goes), Android's focus taken back unless a text is being typed; when it goes, what was
     * typed is forgotten.
     */
    fun windowFocus(has: Boolean) {
        if (has) {
            if (view.rootView.findFocus() !is EditText) view.requestFocus()
            view.requestPointerCapture()
        } else {
            typing = Typing()
            letters.clear()
            cancelClick()
        }
    }

    /** Starts an app over the home as the grid does, OS 7's zoom; refused or missing, logged, never thrown. */
    private fun start(intent: Intent): Boolean = home.start(intent)
}

/**
 * Holds Android's focus so the trackpad's captured moves come to it, and draws nothing: the
 * focus is drawn by the home itself (banner, tab, icon underlay).
 */
internal class KeyTarget(context: Context, private val onPointer: (MotionEvent) -> Boolean) : View(context) {
    init {
        layoutParams = FrameLayout.LayoutParams(1, 1)
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onCapturedPointerEvent(event: MotionEvent) = onPointer(event)
}

/** A list over the home that takes the trackpad first while it is open: the notifications. */
internal interface HomeList {
    val isOpen: Boolean

    /** Takes a step: true if it moved its own focus (or keeps the step), false to leave it to the home. */
    fun step(dir: Dir): Boolean

    /** One of its lines has the focus: the banner shows none. */
    val lineFocused: Boolean

    /** A click: true if it took it (a line focused). */
    fun click(): Boolean

    /** A touch: its lines' focus gone. */
    fun clearLineFocus()
}
