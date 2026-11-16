package dev.bold7.launcher.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.NinePatch
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import dev.bold7.design.Curves
import dev.bold7.design.Durations
import dev.bold7.design.HomeDimens
import dev.bold7.design.toInterpolator
import dev.bold7.launcher.R
import dev.bold7.launcher.switcher.startSwitcher
import dev.bold7.launcher.hub.HubBadges
import dev.bold7.launcher.hub.iconSplat
import dev.bold7.launcher.keys.Focus
import dev.bold7.launcher.keys.focusName
import dev.bold7.launcher.keys.scrollTo
import dev.bold7.launcher.quickstep.WindowZoom
import dev.bold7.launcher.quickstep.spring
import android.view.Choreographer
import dev.bold7.platform.RemoteLaunches
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.TextDialog
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Bold7/Home"

/** Where the tray starts, under the banner and its row: 140 (BBOS7-VISUEL §1). */
private const val TRAY_TOP = HomeDimens.BANNER + HomeDimens.INDICATOR_ROW

/** The tab lowered: one row under it at the bottom of the screen (sim-02, C; D for the square). */
private const val TAB_DOWN = 720 - HomeDimens.ROW - HomeDimens.TAB - TRAY_TOP

/** The grid raised starts right under the tab, as on sim-06 (HomeDimens.GRID_TOP_MARGIN); lowered too. */
private const val GRID_TOP = TRAY_TOP + HomeDimens.TAB + HomeDimens.GRID_TOP_MARGIN
private const val GRID_DOWN = 720 - HomeDimens.ROW - GRID_TOP

/**
 * The wallpaper under the tray, darkened: black at 40 % lowered (homescreen_filter_background.png,
 * A; 0.6 of the wallpaper measured on sim-02, C), at 65 % raised (0.354 measured on sim-06, C).
 */
private const val VEIL_DOWN = 0.4f
private const val VEIL_UP = 0.646f

/** Where a spring puts its value each frame: a primitive Float, nothing boxed per image. */
private fun interface FloatSink {
    fun at(x: Float)
}

/** A hidden icon shown by Show All (D, by eye). */
private const val HIDDEN_ALPHA = dev.bold7.design.HomeColors.HIDDEN_ICON_ALPHA

/** The icon's margin in its cell: a moved app held between the two goes into a folder (D). */
private const val ICON_SIDE = (HomeDimens.COLUMN - HomeDimens.ICON) / 2f

/** A moved icon held at the grid's edge scrolls it a row this often (by eye). */
private const val EDGE_STEP_MS = 400L

/** Rows the tray shows: 5 raised, 1 lowered (BBOS7-VISUEL §2, D). */
internal fun shownRows(expanded: Boolean) = if (expanded) HomeDimens.ROWS_EXPANDED else 1

/**
 * The BBOS 7 home as views (docs/BBOS7-VISUEL.md §1 to §3): the banner, the wallpaper's veil, the
 * tray (the tab of the views and their grids) raised or lowered. It renders [HomeState] and the
 * trackpad's [Focus], and reports what the user does through [dispatch]; the images are decoded
 * on [io]. Raising, lowering, changing view and scrolling move RenderNode properties only.
 */
class HomeView(
    context: Context,
    private val apps: LauncherAppsSource,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val dispatch: (HomeEvent) -> Unit,
    /** Starts the apps. */
    private val launches: RemoteLaunches,
    /** Hide Icon and Unhide: the user's choice, kept by HomeModel. */
    private val setHidden: (App, Boolean) -> Unit,
) : FrameLayout(context) {
    /** The home's Options (menu, guide 7.1 p. 24 and 44): Launch by Typing, set by LauncherActivity. */
    var onOptions: () -> Unit = {}

    /**
     * The tray (veil, tab, grids, folder band) gone while the search shows (sim-54: no drawer under
     * the results, the bar at the bottom closing them), back when it closes.
     */
    fun setTrayHidden(hidden: Boolean) {
        searchShown = hidden
        showTray()
    }

    private var searchShown = false

    /**
     * The tray hidden under the search or the list of notifications by its transition alpha, not
     * its alpha, which keeps the veil's darkening (place), nor its visibility, which follows an
     * open folder for its band.
     */
    private fun showTray() {
        val a = if (trayHidden || searchShown) 0f else 1f
        for (v in arrayOf(veil, gridArea, tab, folderHeader)) v.transitionAlpha = a
    }

    /** The banner's taps go to [onBannerTap], a pull down on it to [onBannerPull]. */
    var onBannerTap: (BannerFocus) -> Unit = {}
    var onBannerPull: () -> Unit = {}
    internal val banner = BannerView(context, { onBannerTap(it) }) { onBannerPull() }

    private val veil = View(context).apply { setBackgroundColor(Color.BLACK) }
    private val tab = TabView(context) { context.getString(it.label()) }
    /**
     * The grids' window, under the tab (or the open folder's band): nothing of a grid draws past
     * it, whatever its scroll. Its parent does not clip (the moved icon goes over everything), so
     * it clips itself, its bounds moving with the tray.
     */
    private val gridArea = FrameLayout(context).apply {
        clipChildren = true
        clipBounds = Rect(0, 0, 720, HomeDimens.ROWS_EXPANDED * HomeDimens.ROW)
    }
    private val panels = Panel.entries.associateWith { GridPanel(context) }

    /** The open folder (sim-16): its header under the tab, its apps in a grid of their own. */
    private val folderHeader = FolderHeader(context)
    private val folderPanel = GridPanel(context)
    private var builtFolder: Folder? = null
    private var editFolder = false

    /** The grid shown: the open folder's, or the view's. */
    private val shownPanel get() = if (state.folder != null) folderPanel else panels.getValue(state.panel)

    /** The state last rendered: what the touches below are read against. */
    var state = HomeState()
        private set
    private var focus: Focus? = null
    private val built = HashMap<Panel, List<Tile>>()
    private var builtVersion = -1
    private val first = HashMap<Panel, Int>() // each view's first row shown

    private var folderImage: Bitmap? = null
    private var moveImage: Bitmap? = null
    private var newImage: Bitmap? = null
    /** What is new in the Hub: the stars of the icons. */
    private var badges = HubBadges()
    private val glyphs = HashMap<HomeAction, Bitmap>()

    // The menus (core/ui's MenuView, drawn as BBOS 7's): the short menu of an icon,
    // the full menu of the Menu key, over the whole home.
    private var menu: HomeMenu? = null
    private var menuTarget: Int? = null
    private val menuView = MenuView<HomeAction>(
        context,
        glyph = { glyphs[it] },
        label = { context.getString(it.label()) },
        onEntry = ::chooseEntry,
        onExpand = { menuIndex().let { showMenu(fullMenu(state, it), it) } }, // Full Menu: the icon's actions and the home's
        onClose = ::closeMenu,
    )

    /** Move to Folder's choices (FolderTarget), in a menu of their own. */
    private var picker: Menu<FolderTarget>? = null
    private val pickerView = MenuView<FolderTarget>(
        context,
        glyph = { null },
        label = { it.name.ifEmpty { context.getString(R.string.home_folder_root) } },
        onEntry = ::chooseTarget,
        onExpand = {},
        onClose = ::closeMenu,
    )

    /** A menu is open: the keys are its own. */
    val menuOpen get() = menu != null || picker != null
    private val today = CalendarDay()


    private class Images(val banner: BannerImages, val tab: TabImages, val focus: NinePatch, val folder: Bitmap, val move: Bitmap, val new: Bitmap, val folderBand: Bitmap, val folderIcons: Bitmap, val glyphs: Map<HomeAction, Bitmap>)

    private fun HomeAction.label() = when (this) {
        HomeAction.LAUNCH -> R.string.home_launch
        HomeAction.MARK_FAVOURITE -> R.string.home_mark_favourite
        HomeAction.UNMARK_FAVOURITE -> R.string.home_unmark_favourite
        HomeAction.MOVE -> R.string.home_move
        HomeAction.MOVE_TO_FOLDER -> R.string.home_move_to_folder
        HomeAction.OPEN_FOLDER -> R.string.home_open_folder
        HomeAction.EDIT_FOLDER -> R.string.home_edit_folder
        HomeAction.DELETE_FOLDER -> R.string.home_delete_folder
        HomeAction.HIDE -> R.string.home_hide
        HomeAction.UNHIDE -> R.string.home_unhide
        HomeAction.DELETE -> R.string.home_delete
        HomeAction.MAXIMISE_PANEL -> R.string.home_maximise_panel
        HomeAction.RESTORE_PANEL -> R.string.home_restore_panel
        HomeAction.ADD_FOLDER -> R.string.home_add_folder
        HomeAction.SHOW_ALL -> R.string.home_show_all
        HomeAction.OPTIONS -> R.string.home_options
        HomeAction.SWITCH_APPLICATION -> R.string.home_switch_application
    }

    /** The short menu of the icon at [index] (the trackpad held, a long touch). */
    fun shortMenu(index: Int) {
        shortMenu(state, index)?.let { showMenu(it, index) }
    }

    /** The full menu (the Menu key), with the actions of the icon at [index] if it has the focus. */
    fun fullMenu(index: Int?) = showMenu(fullMenu(state, index), index)

    private fun showMenu(m: HomeMenu, target: Int?) {
        menu = m
        menuTarget = target
        menuTile = target?.let(state::tileAt)
        menuView.bind(m)
    }

    /** The tile a menu was opened for: its actions aim at it, wherever it is when one is chosen. */
    private var menuTile: Tile? = null

    /** Where [menuTile] is now in what is shown, null if it is gone. */
    private fun menuIndex(): Int? {
        val t = menuTile ?: return null
        return state.shown.indexOfFirst { if (t is App && it is App) it.component == t.component else it == t }.takeIf { it >= 0 }
    }

    fun closeMenu() {
        menu = null
        picker = null
        menuTarget = null
        menuTile = null
        menuView.bind(null)
        pickerView.bind(null)
    }

    /** A key in an open menu, through core/ui's reducer (Menu.key): the short menu's arrows go round its grid. */
    fun menuKey(key: MenuKey) {
        picker?.let { p ->
            when (val r = p.key(key)) {
                is MenuResult.Show -> {
                    picker = r.menu
                    pickerView.bind(r.menu)
                }
                is MenuResult.Choose -> chooseTarget(r.index)
                MenuResult.Close -> closeMenu()
            }
            return
        }
        val m = menu ?: return
        when (val r = m.key(key)) {
            // Full Menu from the short menu: the home's full menu, the icon's actions first.
            is MenuResult.Show -> if (m.short && !r.menu.short) menuIndex().let { showMenu(fullMenu(state, it), it) } else showMenu(r.menu, menuTarget)
            is MenuResult.Choose -> chooseEntry(r.index)
            MenuResult.Close -> closeMenu()
        }
    }

    /** Move to Folder: where the app at [index] can go (D: a menu of the folders, "Home" out of one). */
    private fun pickFolder(index: Int) {
        val targets = folderTargets(state, index)
        if (targets.isEmpty()) return
        menuTarget = index
        menuTile = state.tileAt(index)
        picker = Menu(targets.map { MenuEntry(it) }, highlighted = 0)
        pickerView.bind(picker)
    }

    private fun chooseTarget(i: Int) {
        val target = picker?.entries?.getOrNull(i)?.action ?: return
        val index = menuIndex() ?: return closeMenu()
        closeMenu()
        dispatch(HomeEvent.MoveToFolder(index, target.place))
    }

    private fun chooseEntry(i: Int) {
        val m = menu ?: return
        val target = menuIndex()
        val action = m.entries.getOrNull(i)?.action ?: return
        val home = menuTile == null // a menu opened on no icon
        closeMenu()
        if (target != null || home) act(action, target)
    }

    private fun act(action: HomeAction, index: Int?) {
        val app = index?.let(state::tileAt) as? App
        when (action) {
            HomeAction.LAUNCH -> index?.let(::open)
            HomeAction.MARK_FAVOURITE, HomeAction.UNMARK_FAVOURITE -> app?.let { dispatch(HomeEvent.Favourite(it.component, action == HomeAction.MARK_FAVOURITE)) }
            HomeAction.MOVE -> index?.let { dispatch(HomeEvent.StartMove(it)) }
            HomeAction.HIDE, HomeAction.UNHIDE -> app?.let { setHidden(it, action == HomeAction.HIDE) }
            // Android asks and uninstalls (Android's own dialog).
            HomeAction.DELETE -> app?.let { startPlain(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", it.packageName, null))) }
            HomeAction.MAXIMISE_PANEL -> dispatch(HomeEvent.Expand(true))
            HomeAction.RESTORE_PANEL -> dispatch(HomeEvent.Expand(false))
            HomeAction.ADD_FOLDER -> askName("") { dispatch(HomeEvent.AddFolder(it)) }
            HomeAction.SHOW_ALL -> dispatch(HomeEvent.ShowAll)
            HomeAction.OPTIONS -> onOptions()
            HomeAction.SWITCH_APPLICATION -> startSwitcher(context, fromHome = true)
            HomeAction.MOVE_TO_FOLDER -> index?.let(::pickFolder)
            HomeAction.OPEN_FOLDER -> index?.let { dispatch(HomeEvent.OpenFolder(it)) }
            HomeAction.EDIT_FOLDER -> index?.let {
                editFolder = true // once the folder shows
                dispatch(HomeEvent.OpenFolder(it))
            }
            HomeAction.DELETE_FOLDER -> index?.let { dispatch(HomeEvent.DeleteFolder(it)) }
        }
    }

    /** The text box of OS 7 for a folder's name (sim-61, C; the label is D), its text cleaned as the layout keeps it. */
    private fun askName(initial: String, then: (String) -> Unit) =
        TextDialog(context, context.getString(R.string.folder_name), initial, ::folderName, hideStatusBar = true, onDone = then).show()

    private fun decode(id: Int): Bitmap = BitmapFactory.decodeResource(resources, id)

    private fun Panel.label() = when (this) {
        Panel.FREQUENT -> R.string.panel_frequent
        Panel.ALL -> R.string.panel_all
        Panel.FAVOURITES -> R.string.panel_favourites
        Panel.MEDIA -> R.string.panel_media
        Panel.DOWNLOADS -> R.string.panel_downloads
    }

    fun render(next: HomeState) {
        val before = state
        state = next
        // A view is built again only when what it shows changes, or the icons themselves may have
        // (the hidden ones change look with Show All): a step of Move builds the grid shown alone.
        val icons = next.appsVersion != builtVersion || next.showAll != before.showAll || next.hiding != before.hiding
        builtVersion = next.appsVersion
        for ((panel, view) in panels) {
            val items = next.items(panel)
            if (!icons && built[panel] == items) continue
            built[panel] = items
            view.removeAllViews()
            items.forEachIndexed { i, tile -> view.addView(tileView(tile, i)) }
        }
        val folder = next.folder
        if (icons || folder != builtFolder) {
            builtFolder = folder
            folderPanel.removeAllViews()
            folder?.apps?.forEachIndexed { i, app -> folderPanel.addView(tileView(app, i)) }
        }
        if (folder != null) folderHeader.show(folder.name)
        if (editFolder && folder != null) {
            editFolder = false
            askName(folder.name) { dispatch(HomeEvent.RenameFolder(it)) }
        }
        if ((folder != null) != (before.folder != null)) {
            if (folder != null) {
                folderFirst = 0 // each folder opens on its first row
                folderPanel.translationY = 0f
            }
            folderHeader.visibility = if (folder != null) VISIBLE else GONE
            showPanels(animate = false)
        }
        if (next.panel != before.panel) {
            tab.show(next.panel)
            showPanels(animate = true, from = before.panel)
        }
        if (next.expanded != before.expanded) expand(next.expanded)
        // The moved icon's underlay, on the grid shown: a property of its tiles, nothing built.
        val grid = shownPanel
        for (i in 0 until grid.childCount) grid.tile(i)?.moveUnderlay = moveImage.takeIf { i == next.moving }
        showFocus(focus)
    }

    /**
     * The Hub's news, from its feed: a star on each icon whose app holds something new, Messages
     * for anything, Text Messages for a text (iconSplat). Only a property of the tiles built.
     */
    fun showBadges(next: HubBadges) {
        if (next == badges) return
        badges = next
        for (view in panels.values + folderPanel) view.forEachTile { it.splat = splatOf(it.tile) }
    }

    private fun splatOf(tile: Tile): Bitmap? = newImage.takeIf { tile is App && iconSplat(tile.packageName, { apps.tableIcon(tile) }, badges, tile.shortcutId) }

    private fun tileView(tile: Tile, index: Int): TileView {
        val icon = when (tile) {
            is App -> apps.icon(tile)
            is Folder -> folderImage
        }
        return TileView(context, tile, icon, today.takeIf { tile is App && apps.showsDay(tile) }).apply {
            // A hidden icon Show All shows: at half its opacity (D).
            if (tile is App && state.hides(tile)) alpha = HIDDEN_ALPHA
            splat = splatOf(tile)
            setOnClickListener { if (state.moving != null) drop(index) else open(index) }
            setOnLongClickListener {
                longTouch(index)
                true
            }
        }
    }

    /** A tap while moving: the icon goes there and stays. */
    private fun drop(index: Int) {
        dispatch(HomeEvent.MoveTo(index))
        dispatch(HomeEvent.EndMove)
    }

    /** Opens the tile at [index] of the view shown: an app starts, first in Frequent. */
    fun open(index: Int) {
        when (val tile = state.tileAt(index)) {
            is App -> {
                // A mail account's icon is a shortcut of Messages, the others activities.
                val started = if (tile.shortcutId != null) {
                    apps.startShortcut(tile, launches.options(WindowZoom.open))
                } else {
                    start(Intent.makeMainActivity(ComponentName(tile.packageName, tile.className)).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                }
                if (started) dispatch(HomeEvent.Launched(tile.component))
            }
            is Folder -> dispatch(HomeEvent.OpenFolder(index))
            null -> Unit
        }
    }

    /**
     * A value let go by the finger, springing to [to] from the speed it had (spring() of
     * quickstep/Spring.kt), a frame at a time on the Choreographer; [apply] sets RenderNode
     * properties only, [done] runs once it rests.
     */
    private inner class Settle(private val apply: FloatSink, private val done: () -> Unit = {}) : Choreographer.FrameCallback {
        private var from = 0f
        private var velocity = 0f
        private var start = 0L
        var running = false
            private set

        /** Where it goes, and its speed at the last frame: a new target springs on from it, no jump. */
        var to = 0f
            private set
        var speed = 0f
            private set

        fun start(from: Float, velocity: Float, to: Float) {
            this.from = from
            this.velocity = velocity
            this.to = to
            speed = velocity
            start = System.nanoTime() // the Choreographer's clock
            if (!running) {
                running = true
                choreographer.postFrameCallback(this)
            }
        }

        fun cancel() {
            if (!running) return
            running = false
            choreographer.removeFrameCallback(this)
        }

        override fun doFrame(now: Long) {
            if (!running) return
            val s = spring(from, velocity, to, (now - start).coerceAtLeast(0L) / 1e9f)
            if (s.settled) {
                running = false
                apply.at(to)
                done()
            } else {
                speed = s.velocity
                apply.at(s.position)
                choreographer.postFrameCallback(this)
            }
        }
    }

    private val choreographer = Choreographer.getInstance()

    // Raising and lowering the tray. From a key or a menu (Maximise Panel, a click on the tab):
    // 250 ms (animation-duration-portrait) in QUADRATIC_INOUT (animation-easingcurve-tabmanager-vertical),
    // A; D the pairing. Under the finger it follows it, then springs at its speed (D).
    private var raised = 0f
    private var raiseFrom = 0f
    private var raiseTo = 0f
    private val raise = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Durations.PORTRAIT_MS
        interpolator = Curves.QuadraticInOut.toInterpolator()
        addUpdateListener { place(raiseFrom + (raiseTo - raiseFrom) * it.animatedFraction) }
    }
    private val raiseSettle = Settle({ place(it / TAB_DOWN) })

    /** The finger's raising speed (px/s, up positive) the next expand() springs from; null: a key's. */
    private var fingerRaise: Float? = null

    private fun expand(up: Boolean) {
        raise.cancel()
        raiseSettle.cancel()
        val to = if (up) 1f else 0f
        val v = fingerRaise
        fingerRaise = null
        when {
            !isAttachedToWindow -> place(to)
            v != null -> raiseSettle.start(raised * TAB_DOWN, v, to * TAB_DOWN)
            else -> {
                raiseFrom = raised
                raiseTo = to
                raise.start()
            }
        }
        scroll()
    }

    /** The tray at [f] of the way up: a few RenderNode properties, nothing laid out. */
    private fun place(f: Float) {
        raised = f
        val down = 1f - f
        tab.translationY = TAB_DOWN * down
        folderHeader.translationY = TAB_DOWN * down
        veil.translationY = TAB_DOWN * down
        veil.alpha = VEIL_DOWN + (VEIL_UP - VEIL_DOWN) * f
        gridArea.translationY = GRID_DOWN * down
    }

    // Changing view. From the trackpad: 300 ms in QUADRATIC_OUT (animation-duration-tabmanager-portrait,
    // A), the grids sliding a width round the loop as the tab's names do. Under the finger the
    // grids and the names follow it, then spring at its speed (D). A view off screen keeps its
    // display list (transition alpha 0; docs/PERF.md).
    private var outgoing: GridPanel? = null
    private var slideStep = 0
    private val slide = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = Durations.TRANSITION_MS
        interpolator = Curves.QuadraticOut.toInterpolator()
        addUpdateListener { slideTo(it.animatedFraction) }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = hideOutgoing()
        })
    }

    // The finger's slide: [slideA] goes to 0, [slideB] follows [slideBOffset] px away.
    private var slideA: View? = null
    private var slideB: View? = null
    private var slideBOffset = 0f
    private val slideSettle = Settle({ x -> slideAt(x) }, ::hideOutgoing)

    private fun slideAt(x: Float) {
        slideA?.translationX = x
        slideB?.translationX = x + slideBOffset
        tab.setShift(x / 2) // the names are half a width apart
    }

    private fun hideOutgoing() {
        outgoing?.let {
            if (it !== panels[state.panel]) it.transitionAlpha = 0f
            it.translationX = 0f
        }
        outgoing = null
    }

    /** The finger let a view go at [offset] px, [velocity] px/s, after the drag: the next showPanels springs. */
    private var fingerSlide: Pair<Float, Float>? = null

    private fun showPanels(animate: Boolean, from: Panel? = null) {
        slide.cancel()
        slideSettle.cancel()
        hideOutgoing()
        landOtherGrid()
        val step = when {
            from == null || !animate || !isAttachedToWindow -> 0
            from.next(1) == state.panel -> 1
            from.next(-1) == state.panel -> -1
            else -> 0
        }
        outgoing = if (step != 0 && from != null) panels.getValue(from) else null
        slideStep = step
        val inFolder = state.folder != null
        for ((panel, view) in panels) {
            val on = !inFolder && panel == state.panel || view === outgoing
            view.transitionAlpha = if (on) 1f else 0f
            view.translationX = 0f
        }
        folderPanel.transitionAlpha = if (inFolder) 1f else 0f
        scroll()
        val finger = fingerSlide
        fingerSlide = null
        if (step == 0) return
        if (finger != null) {
            slideA = panels.getValue(state.panel)
            slideB = outgoing
            slideBOffset = -step * width.toFloat()
            slideAt(finger.first)
            slideSettle.start(finger.first, finger.second, 0f)
        } else {
            tab.slideIn(step)
            slideTo(0f)
            slide.start()
        }
    }

    private fun slideTo(f: Float) {
        val w = width.toFloat()
        panels.getValue(state.panel).translationX = slideStep * w * (1f - f)
        outgoing?.translationX = -slideStep * w * f
    }

    /**
     * The view shown keeps its focused row in sight, a row at a time, springing there (sim-09,
     * sim-10). A step that comes while the grid still springs, a fling's or the step before's,
     * moves its target on: the focus never goes under the rows shown.
     */
    private fun scroll() {
        val focusRow = (focus as? Focus.Icon)?.index?.div(HomeDimens.COLUMNS)
        val row = scrollTo(shownFirst, focusRow, shownRows(state.expanded), shownPanel.rows) ?: return
        rows(row, animate = isAttachedToWindow)
    }

    // Each view keeps its row; the open folder starts at its first.
    private var folderFirst = 0
    private val shownFirst get() = if (state.folder != null) folderFirst else first[state.panel] ?: 0

    /** The shown grid to [row]: at once, or springing from where it is at [velocity] px/s, by default the speed it has. */
    private fun rows(row: Int, animate: Boolean, velocity: Float = if (scrollSettle.running && scrolledGrid === shownPanel) scrollSettle.speed else 0f) {
        if (state.folder != null) folderFirst = row else first[state.panel] = row
        val grid = shownPanel
        val to = -row * HomeDimens.ROW.toFloat()
        scrollSettle.cancel()
        scrolledGrid = grid
        if (animate) scrollSettle.start(grid.translationY, velocity, to) else grid.translationY = to
    }

    /** A spring of another grid than the one shown (the view or the folder changed): it lands at once. */
    private fun landOtherGrid() {
        if (!scrollSettle.running || scrolledGrid === shownPanel) return
        scrollSettle.cancel()
        scrolledGrid.translationY = scrollSettle.to
    }

    /** The grid [scrollSettle] moves: the one shown when it started, never the next one shown. */
    private var scrolledGrid = folderPanel
    private val scrollSettle = Settle({ scrolledGrid.translationY = it })

    /**
     * The tray, its veil and an open folder's header shown, or not under the list of
     * notifications, which takes their place (sim-26, C), and takes their touches. Their own
     * alphas stay the tray's.
     */
    fun trayShown(shown: Boolean) {
        trayHidden = !shown
        showTray()
    }

    /** Shows the trackpad's focus (HomeKeys): on the banner, the tab or an icon, its name in the tab. */
    internal fun showFocus(next: Focus?) {
        val before = focus
        focus = next
        banner.focus = (next as? Focus.Top)?.target
        tab.focused = next == Focus.Tab
        val icon = (next as? Focus.Icon)?.index
        for ((panel, view) in panels) view.focused = icon.takeIf { panel == state.panel && state.folder == null }
        folderPanel.focused = icon.takeIf { state.folder != null }
        if (next != before) tab.showName(focusName(state, next))
        scroll()
    }

    // The tray under the finger (TrayGesture.kt). On the tray: sideways the views turn, up and down
    // it rises, scrolls and lowers, everything following the finger and springing at its speed.
    // A moved icon (Move, or a drag right after the long press that opened its short menu) follows the finger; the others make room, the grid scrolls at its edges, and it
    // lands where it is let go, into a folder if let go over one's icon, out of the
    // open folder if over its header.
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var dragging: Axis? = null
    private var raising = false // an up-down drag that raises or lowers, not one that scrolls
    private var dragStart = 0f // the raised fraction, or the grid's offset, when it started
    private var neighbour: GridPanel? = null
    private var tracker: VelocityTracker? = null

    /** The tile a long press just opened the short menu of: a drag from there moves it. */
    private var longPressed: Int? = null
    private var carried: TileView? = null // the moved icon under the finger
    private var into: Int? = null // the folder it would go into
    private var outOfFolder = false
    private var edge = 0

    private fun gridTop() = GRID_TOP + gridArea.translationY

    private var trayHidden = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (trayHidden) return false
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                dragging = null
                longPressed = null
                (tracker ?: VelocityTracker.obtain().also { tracker = it }).clear()
                tracker?.addMovement(e)
                // A finger on a tray still moving catches it where it is: no tap of the icon under it.
                caught = raise.isRunning || raiseSettle.running || slide.isRunning || slideSettle.running || scrollSettle.running
                if (caught) {
                    slideOrigin = if (state.folder == null) panels.getValue(state.panel).translationX else 0f
                    stopMotion()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (dragging != null || carried != null) return true
                val axis = axis(x - downX, y - downY, slop) ?: return false
                val lifted = longPressed
                val moving = state.moving
                when {
                    // Only where Move works (All, Favourites, an open folder); the state follows on the next render.
                    lifted != null && (state.panel.arranged() || state.folder != null) -> {
                        closeMenu()
                        dispatch(HomeEvent.StartMove(lifted))
                        carry(lifted, x, y)
                    }
                    lifted != null -> return false
                    moving != null && y >= gridTop() -> carry(moving, x, y)
                    menuOpen || downY < TRAY_TOP + tab.translationY -> return false
                    else -> startDrag(axis, y - downY)
                }
                return dragging != null || carried != null
            }
        }
        return false
    }

    /** A long touch on the tile at [index]: its short menu, and a drag from there moves it. */
    private fun longTouch(index: Int) {
        shortMenu(index)
        longPressed = index
    }

    private var caught = false // the down caught a moving tray
    private var slideOrigin = 0f // the view's offset when a slide was caught: the drag goes on from it

    private fun stopMotion() {
        raise.cancel()
        raiseSettle.cancel()
        slide.cancel()
        slideSettle.cancel()
        scrollSettle.cancel()
    }

    /** A caught tray let go without a drag: it settles from where it is, as a release at rest. */
    private fun resettle() {
        hideOutgoing()
        neighbour?.let { it.transitionAlpha = 0f; it.translationX = 0f }
        neighbour = null
        raiseSettle.start(raised * TAB_DOWN, 0f, if (state.expanded) TAB_DOWN.toFloat() else 0f)
        if (slideOrigin != 0f) {
            slideA = panels.getValue(state.panel)
            slideB = null
            slideSettle.start(slideOrigin, 0f, 0f)
        }
        slideOrigin = 0f
        releaseScroll(0f)
    }

    private fun startDrag(axis: Axis, dy: Float) {
        stopMotion()
        hideOutgoing()
        if (axis != Axis.SIDEWAYS) slideOrigin = 0f
        dragging = axis
        parent?.requestDisallowInterceptTouchEvent(true)
        if (axis == Axis.UPDOWN) {
            // Lowered, it rises; raised, a drag down from the first row lowers it, the others scroll.
            raising = !state.expanded || dy > 0 && shownFirst == 0
            dragStart = if (raising) raised else shownPanel.translationY
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        tracker?.addMovement(e)
        val dx = e.x - downX
        val dy = e.y - downY
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> when {
                carried != null -> carryTo(e.x, e.y)
                dragging == null -> axis(dx, dy, slop)?.let { if (!menuOpen && downY >= TRAY_TOP + tab.translationY) startDrag(it, dy) }
                dragging == Axis.SIDEWAYS -> slideBy(slideOrigin + dx)
                raising -> place(raised(dragStart, dy, TAB_DOWN.toFloat()))
                else -> shownPanel.translationY = scrolled(dragStart, dy, shownPanel.rows, shownRows(state.expanded))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val t = tracker
                t?.computeCurrentVelocity(1000)
                val vx = if (e.actionMasked == MotionEvent.ACTION_UP) t?.xVelocity ?: 0f else 0f
                val vy = if (e.actionMasked == MotionEvent.ACTION_UP) t?.yVelocity ?: 0f else 0f
                when {
                    // Taken away (a call over the home): the move is put back, not dropped.
                    carried != null && e.actionMasked == MotionEvent.ACTION_CANCEL -> drop()
                    carried != null -> land()
                    dragging == null && caught -> resettle()
                    dragging == Axis.SIDEWAYS -> releaseSlide(slideOrigin + dx, vx)
                    dragging == Axis.UPDOWN && raising -> releaseRaise(vy)
                    dragging == Axis.UPDOWN -> releaseScroll(vy)
                }
                dragging = null
                longPressed = null
                caught = false
                slideOrigin = 0f
            }
        }
        return true
    }

    private fun slideBy(dx: Float) {
        val w = width.toFloat()
        val step = if (dx < 0) 1 else -1
        val next = panels.getValue(state.panel.next(step))
        if (state.folder != null) return // a folder does not turn
        if (neighbour !== next) {
            neighbour?.let { it.transitionAlpha = 0f; it.translationX = 0f }
            neighbour = next
            next.transitionAlpha = 1f
        }
        panels.getValue(state.panel).translationX = dx
        next.translationX = dx + step * w
        tab.setShift(dx / 2)
    }

    private fun releaseSlide(dx: Float, vx: Float) {
        val w = width.toFloat()
        val step = panelStep(dx, vx, w)
        val n = neighbour
        neighbour = null
        if (state.folder != null) return
        if (step == 0) {
            // Back to its own view.
            outgoing = n
            slideA = panels.getValue(state.panel)
            slideB = n
            slideBOffset = if (dx < 0) w else -w
            slideSettle.start(dx, vx, 0f)
            return
        }
        fingerSlide = (dx + step * w) to vx
        dispatch(HomeEvent.ShowPanel(state.panel.next(step)))
    }

    private fun releaseRaise(vy: Float) {
        val up = settlesRaised(raised, vy)
        if (up != state.expanded) {
            fingerRaise = -vy
            dispatch(HomeEvent.Expand(up))
        } else {
            raiseSettle.start(raised * TAB_DOWN, -vy, if (up) TAB_DOWN.toFloat() else 0f)
        }
    }

    private fun releaseScroll(vy: Float) {
        rows(restRow(shownPanel.translationY, vy, shownPanel.rows, shownRows(state.expanded)), animate = true, velocity = vy)
    }

    /** The moved icon at [index] lifted under the finger at ([x], [y]); lowered, the tray rises (D). */
    private fun carry(index: Int, x: Float, y: Float) {
        val tile = state.tileAt(index) ?: return
        parent?.requestDisallowInterceptTouchEvent(true)
        if (!state.expanded) dispatch(HomeEvent.Expand(true))
        val icon = when (tile) {
            is App -> apps.icon(tile)
            is Folder -> folderImage
        }
        carried = TileView(context, tile, icon, null).also {
            addView(it, LayoutParams(HomeDimens.COLUMN, HomeDimens.ROW))
            it.translationX = x - HomeDimens.COLUMN / 2f
            it.translationY = y - HomeDimens.ROW / 2f
        }
        carryTo(x, y)
    }

    private fun carryTo(x: Float, y: Float) {
        val c = carried ?: return
        c.translationX = x - HomeDimens.COLUMN / 2f
        c.translationY = y - HomeDimens.ROW / 2f
        val at = state.moving ?: return
        val gy = y - gridTop()
        outOfFolder = state.folder != null && gy < 0
        val cell = cellUnder(x, gy, shownFirst, state.shown.size)
        val inX = x - (cell % HomeDimens.COLUMNS) * HomeDimens.COLUMN
        val inY = gy - (cell / HomeDimens.COLUMNS - shownFirst) * HomeDimens.ROW
        val inIcon = inX in ICON_SIDE..(HomeDimens.COLUMN - ICON_SIDE) && inY in 0f..HomeDimens.ICON.toFloat()
        into = null
        when (val drop = dropAt(state.shown, at, cell, inIcon)) {
            is Drop.IntoFolder -> into = drop.index
            is Drop.At -> if (!outOfFolder && drop.index != at) dispatch(HomeEvent.MoveTo(drop.index))
        }
        // Over the open folder's band the icon leaves the folder: no scroll up there.
        val dir = if (outOfFolder) 0 else edgeScroll(gy, HomeDimens.ROW * shownRows(state.expanded).toFloat())
        if (dir != edge) {
            edge = dir
            removeCallbacks(edgeStep)
            if (dir != 0) postDelayed(edgeStep, EDGE_STEP_MS)
        }
    }

    // At an edge, a row every EDGE_STEP_MS while the icon stays there (D).
    private val edgeStep = object : Runnable {
        override fun run() {
            val max = (shownPanel.rows - shownRows(state.expanded)).coerceAtLeast(0)
            rows((shownFirst + edge).coerceIn(0, max), animate = true)
            carried?.let { carryTo(it.translationX + HomeDimens.COLUMN / 2f, it.translationY + HomeDimens.ROW / 2f) }
            if (edge != 0) postDelayed(this, EDGE_STEP_MS)
        }
    }

    /** The finger taken away while it moved an icon: the move is put back, the icon goes. */
    private fun drop() {
        val c = carried ?: return
        carried = null
        removeCallbacks(edgeStep)
        edge = 0
        into = null
        outOfFolder = false
        removeView(c)
        dispatch(HomeEvent.Back) // during a move, Back puts it back
    }

    /** Let go: the icon springs to its place from the finger, the move ends, into a folder or out of one. */
    private fun land() {
        val c = carried ?: return
        carried = null
        removeCallbacks(edgeStep)
        edge = 0
        val at = state.moving
        val folder = into?.let { if (state.folder == null) state.placeOf(it) else null }
        val out = outOfFolder
        into = null
        outOfFolder = false
        dispatch(HomeEvent.EndMove)
        if (at != null && folder != null) dispatch(HomeEvent.MoveToFolder(at, folder))
        if (at != null && out) dispatch(HomeEvent.MoveToFolder(at, null))
        val toX = ((at ?: 0) % HomeDimens.COLUMNS) * HomeDimens.COLUMN.toFloat()
        val toY = gridTop() + ((at ?: 0) / HomeDimens.COLUMNS - shownFirst) * HomeDimens.ROW.toFloat()
        val v = tracker
        val vx = v?.xVelocity ?: 0f
        val vy = v?.yVelocity ?: 0f
        val fromX = c.translationX
        val fromY = c.translationY
        var settledX = false
        var settledY = false
        val gone = { if (settledX && settledY) removeView(c) }
        Settle({ c.translationX = it }, { settledX = true; gone() }).start(fromX, vx, toX)
        Settle({ c.translationY = it }, { settledY = true; gone() }).start(fromY, vy, toY)
    }

    // The day on the calendar icon. Read again each time the home shows, and while it shows,
    // at midnight and when the clock or the time zone is set: no alarm, no wake-up of our own.
    private var dateListened = false
    private val dateChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showToday()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            if (!dateListened) {
                val filter = IntentFilter(Intent.ACTION_DATE_CHANGED).apply {
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }
                context.registerReceiver(dateChanged, filter, Context.RECEIVER_NOT_EXPORTED)
                dateListened = true
            }
            showToday()
        } else if (dateListened) {
            context.unregisterReceiver(dateChanged)
            dateListened = false
        }
    }

    private fun showToday() {
        if (!today.show(LocalDate.now().dayOfMonth)) return
        for (view in panels.values + folderPanel) view.forEachTile { tile -> if ((tile.tile as? App)?.let(apps::showsDay) == true) tile.invalidate() }
    }

    override fun onDetachedFromWindow() {
        raise.cancel()
        slide.cancel()
        raiseSettle.cancel()
        slideSettle.cancel()
        scrollSettle.cancel()
        removeCallbacks(edgeStep)
        tracker?.recycle()
        tracker = null
        super.onDetachedFromWindow()
    }

    /**
     * Starts an activity over the home with Android's animation (the uninstall prompt). A refused
     * start is logged, never thrown: it would take the whole process down, quickstep service
     * included.
     */
    private fun startPlain(intent: Intent) = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), RemoteLaunches.plain())
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "nothing to start for $intent", e)
    }

    /** Starts an app over the home, OS 7's zoom; refused or missing, logged, false. */
    fun start(intent: Intent): Boolean = try {
        launches.start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), WindowZoom.open)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "nothing to start for $intent", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "start refused for $intent", e)
        false
    }

    // Last in the class: Kotlin runs the initializers in the order they are written, and this
    // one uses the animators, springs and maps declared above.
    init {
        clipChildren = false
        addView(veil, LayoutParams(LayoutParams.MATCH_PARENT, 720 - TRAY_TOP).apply { topMargin = TRAY_TOP })
        addView(gridArea, LayoutParams(LayoutParams.MATCH_PARENT, HomeDimens.ROWS_EXPANDED * HomeDimens.ROW).apply { topMargin = GRID_TOP })
        addView(tab, LayoutParams(LayoutParams.MATCH_PARENT, HomeDimens.TAB).apply { topMargin = TRAY_TOP })
        addView(banner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(folderHeader, LayoutParams(LayoutParams.MATCH_PARENT, FOLDER_HEADER).apply { topMargin = TRAY_TOP + HomeDimens.TAB })
        addView(menuView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(pickerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        gridArea.addView(folderPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        folderHeader.visibility = GONE
        folderPanel.transitionAlpha = 0f
        for (p in panels.values) gridArea.addView(p, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        tab.setOnClickListener { dispatch(HomeEvent.Expand(!state.expanded)) }
        place(0f)
        showPanels(animate = false)
        scope.launch {
            val images = withContext(io) {
                val banner = BannerImages(resources)
                Images(
                    banner,
                    TabImages(decode(R.drawable.home_tabmanager_header_line_top), decode(R.drawable.home_tabmanager_header_line_bottom), banner.focus, decode(R.drawable.home_homescreen_mask_p)),
                    banner.focus,
                    decode(R.drawable.home_folder),
                    decode(R.drawable.home_icon_move_underlay),
                    decode(R.drawable.home_new_overlay),
                    decode(R.drawable.home_background_folderview),
                    decode(R.drawable.home_net_rim_folder_icons32x32),
                    mapOf(
                        HomeAction.LAUNCH to R.drawable.home_sm_launch,
                        HomeAction.MARK_FAVOURITE to R.drawable.home_sm_mark_favourite,
                        HomeAction.UNMARK_FAVOURITE to R.drawable.home_sm_unmark_favourite,
                        HomeAction.MOVE to R.drawable.home_sm_move,
                        HomeAction.HIDE to R.drawable.home_sm_hide,
                        HomeAction.UNHIDE to R.drawable.home_sm_unhide,
                        HomeAction.DELETE to R.drawable.home_sm_delete,
                        HomeAction.ADD_FOLDER to R.drawable.home_sm_add_folder,
                        HomeAction.MOVE_TO_FOLDER to R.drawable.home_sm_move_to_folder,
                        HomeAction.OPEN_FOLDER to R.drawable.home_sm_folder_open,
                        HomeAction.EDIT_FOLDER to R.drawable.home_sm_rename_folder,
                        HomeAction.DELETE_FOLDER to R.drawable.home_sm_delete_folder,
                    ).mapValues { decode(it.value) },
                )
            }
            banner.images(images.banner)
            tab.images(images.tab)
            for (p in panels.values) p.focusImage = images.focus
            folderImage = images.folder
            moveImage = images.move
            newImage = images.new
            folderHeader.images(images.folderBand, images.folderIcons)
            folderPanel.focusImage = images.focus
            glyphs.putAll(images.glyphs)
            builtVersion = -1 // the folders' icon is in: build again
            render(state)
        }
    }
}
