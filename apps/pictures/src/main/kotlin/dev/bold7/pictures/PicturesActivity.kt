package dev.bold7.pictures

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.ScreenStack
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.TitleBar
import dev.bold7.ui.bb10List
import dev.bold7.ui.status
import dev.bold7.ui.themeBitmap

/** A page of Pictures: the categories, or a category's thumbnails. */
sealed interface Page {
    /** The first screen; [focus] 0 is the Camera row, then the categories. */
    data class Categories(val focus: Int = 1) : Page
    /** A category's grid; [focus] -1 is its Camera row (Camera Pictures only, sim-367). */
    data class Thumbnails(val source: Source, val title: String, val focus: Int = 0) : Page
}

/**
 * Pictures of BBOS 7.1 (docs/BBOS7-VISUEL.md §37): the categories (sim-344, C), then a category's
 * thumbnails (sim-345, sim-367), whose click opens the picture ([ViewerActivity]); the pages are a
 * [ScreenStack], Escape goes back. The pictures are MediaStore's, read off the main thread, their
 * thumbnails decoded two at a time off it.
 *
 * `su -c 'am start -n dev.bold7.pictures/.PicturesActivity --ei rows 500'`: a category of 500 made-up
 * pictures, open on its grid, nothing read (tools/perf.sh, APP mode).
 */
open class PicturesActivity : Activity() {
    /** Select Picture ([SelectPictureActivity]): a picture's click gives it back instead of opening it. */
    protected open val selecting = false

    companion object {
        private const val EXTRA_ROWS = "rows"
        private const val DEMO_MAX = 5000
    }

    private lateinit var title: TitleBar
    private lateinit var list: ListView
    private lateinit var gridPage: LinearLayout
    private lateinit var cameraRow: CategoryRowView
    private lateinit var grid: GridView
    private lateinit var bar: PictureBar
    private lateinit var menuView: MenuView<Act>
    private lateinit var acts: PictureActs

    private var pages = ScreenStack<Page>(Page.Categories())
    private var all: List<Picture> = emptyList()
    private var cats: List<Category> = emptyList()
    private var shown: List<Picture> = emptyList()
    private var menu: Menu<Act>? = null
    private var held = false
    private var menuClick = false
    private var top = 0
    private val glyphs = HashMap<Act, Bitmap>()
    private var noPicture: Bitmap? = null
    private var noThumb: Bitmap? = null
    private var cameraIcon: Bitmap? = null
    private val loading = HashSet<Long>()
    private val categoryRows = CategoryRows()
    private val cells = Cells()
    private var cell = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val g = graph
        acts = PictureActs(this) { load() }
        title = TitleBar(this, getString(R.string.title))
        list = bb10List(ListView(this), 0).apply {
            adapter = categoryRows
            isFocusable = false
            isHapticFeedbackEnabled = false
            setOnItemClickListener { _, _, position, _ -> focus(position); open() }
            setOnItemLongClickListener { _, _, position, _ -> focus(position); show(shortMenu()); true }
        }
        cameraRow = CategoryRowView(this, camera = true).apply {
            layoutParams = LinearLayout.LayoutParams(match, CategoryRowView.CAMERA_ROW)
            setOnClickListener { focus(-1); open() }
        }
        cell = resources.displayMetrics.widthPixels.let { it / GridLayout(it, 0, 0).columns }
        grid = GridView(this).apply {
            numColumns = GridLayout(resources.displayMetrics.widthPixels, 0, 0).columns
            columnWidth = cell
            stretchMode = GridView.NO_STRETCH
            adapter = cells
            isFocusable = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            selector = android.graphics.drawable.ColorDrawable(0)
            isHapticFeedbackEnabled = false
            setOnItemClickListener { _, _, position, _ -> focus(position); open() }
            setOnItemLongClickListener { _, _, position, _ -> focus(position); show(shortMenu()); true }
        }
        bar = PictureBar(this)
        gridPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(cameraRow)
            addView(grid, LinearLayout.LayoutParams(match, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(match, PictureBar.HEIGHT))
        }
        menuView = MenuView(this, glyph = { glyphs[it] }, label = ::label, onEntry = ::chosen, onExpand = { menu?.let { show(it.expand()) } }, onClose = { show(null) })
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(PictureColors.BACKGROUND)
            addView(LinearLayout(this@PicturesActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title, LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(FrameLayout(this@PicturesActivity).apply {
                    addView(list, FrameLayout.LayoutParams(match, match))
                    addView(gridPage, FrameLayout.LayoutParams(match, match))
                }, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        // No status bar of Android's: the 9900 shows its title bar alone (sim-344).
        window.hideStatusBar()
        val rows = intent.getIntExtra(EXTRA_ROWS, 0).coerceIn(0, DEMO_MAX)
        if (rows > 0) {
            g.demo = Store.demo(rows, System.currentTimeMillis())
            pages = ScreenStack<Page>(Page.Categories()).push(Page.Thumbnails(Source.Library, getString(R.string.picture_library)))
        }
        g.io.post {
            val images = mapOf(
                Act.VIEW_SLIDESHOW to "pic_sm_play", Act.SET_WALLPAPER to "pic_sm_set_as_wallpaper",
                Act.ASSIGN_CONTACT to "pic_sm_set_as_caller_id", Act.SEND to "pic_sm_send_or_share_image",
                Act.DELETE to "pic_sm_delete", Act.ZOOM to "pic_sm_zoom_in", Act.ROTATE to "pic_sm_rotate",
            ).mapValues { decode(it.value) }
            val none = decode("pic_noart_picture_icon")
            val thumb = decode("pic_noart_picture_icon_thumbnail")
            val camera = decode("pic_net_rim_bb_camera_camera")
            runOnUiThread {
                glyphs.putAll(images)
                noPicture = none
                noThumb = thumb
                cameraIcon = camera
                render()
            }
        }
        render()
    }

    private fun decode(name: String): Bitmap = themeBitmap(applicationContext, name)

    override fun onStart() {
        super.onStart()
        load()
    }

    /** MediaStore read again off the main thread (a picture was taken, deleted, or the screen came back). */
    private fun load() {
        val g = graph
        g.io.post {
            val pictures = g.all()
            runOnUiThread {
                // The thumbnails stay cached by their id: a picture changed in place keeps its old one until the cache lets it go (D).
                all = pictures
                render()
            }
        }
    }

    // State

    private fun categoriesNow() = categories(all, Sort.NAME, getString(R.string.camera_pictures), getString(R.string.picture_library))

    private fun render() {
        cats = categoriesNow()
        when (val p = pages.top) {
            is Page.Categories -> {
                title.bind(getString(if (selecting) R.string.select_picture else R.string.title))
                list.visibility = View.VISIBLE
                gridPage.visibility = View.GONE
                if (p.focus > cats.size) pages = replace(p.copy(focus = cats.size))
                categoryRows.notifyDataSetChanged()
                ensureVisible()
            }
            is Page.Thumbnails -> {
                title.bind(p.title)
                list.visibility = View.GONE
                gridPage.visibility = View.VISIBLE
                shown = picturesOf(all, p.source, Sort.NAME)
                val camera = p.source == Source.Camera
                cameraRow.visibility = if (camera) View.VISIBLE else View.GONE
                cameraRow.bind(getString(R.string.camera), "", p.focus == -1, null, cameraIcon)
                val focus = p.focus.coerceIn(if (camera) -1 else 0, maxOf(0, shown.size - 1))
                if (focus != p.focus) pages = replace(p.copy(focus = focus))
                cells.notifyDataSetChanged()
                bindBar()
                ensureVisible()
            }
        }
    }

    private fun replace(p: Page): ScreenStack<Page> = pages.pop()?.push(p) ?: ScreenStack(p)

    private fun bindBar() {
        val p = (pages.top as? Page.Thumbnails)?.let { shown.getOrNull(it.focus) }
        bar.bind(p?.name.orEmpty(), p?.let { day(it.taken) }.orEmpty())
    }

    private fun focus(at: Int) {
        pages = replace(when (val p = pages.top) {
            is Page.Categories -> p.copy(focus = at)
            is Page.Thumbnails -> p.copy(focus = at)
        })
        when (pages.top) {
            is Page.Categories -> list.invalidateViews()
            is Page.Thumbnails -> { grid.invalidateViews(); cameraRow.bind(getString(R.string.camera), "", at == -1, null, cameraIcon); bindBar() }
        }
        ensureVisible()
    }

    private fun ensureVisible() {
        when (val p = pages.top) {
            is Page.Categories -> if (p.focus < list.firstVisiblePosition || p.focus > list.lastVisiblePosition) list.setSelection(p.focus)
            is Page.Thumbnails -> {
                if (p.focus < 0) return
                val layout = GridLayout(grid.width, grid.height, shown.size)
                if (layout.width == 0 || layout.height == 0) return grid.post(::ensureVisible).let { }
                val current = grid.firstVisiblePosition / layout.columns + if ((grid.getChildAt(0)?.top ?: 0) < 0) 1 else 0
                val next = layout.topFor(p.focus, current)
                if (next != current || (grid.getChildAt(0)?.top ?: 0) < 0) grid.setSelection(next * layout.columns)
            }
        }
    }

    private fun step(s: Step) {
        when (val p = pages.top) {
            is Page.Categories -> when (s) {
                Step.UP -> focus((p.focus - 1).coerceAtLeast(0))
                Step.DOWN -> focus((p.focus + 1).coerceAtMost(cats.size))
                else -> Unit
            }
            is Page.Thumbnails -> {
                val columns = GridLayout(grid.width, grid.height, shown.size).columns
                val camera = p.source == Source.Camera
                when {
                    p.focus == -1 -> if (s == Step.DOWN && shown.isNotEmpty()) focus(0)
                    camera && s == Step.UP && p.focus < columns -> focus(-1)
                    camera && s == Step.LEFT && p.focus == 0 -> focus(-1)
                    else -> focus(move(p.focus, shown.size, columns, s))
                }
            }
        }
    }

    // Actions

    private fun focusedCategory(): Category? = (pages.top as? Page.Categories)?.let { cats.getOrNull(it.focus - 1) }

    private fun focusedPicture(): Picture? = (pages.top as? Page.Thumbnails)?.let { shown.getOrNull(it.focus) }

    private fun open() {
        when (val p = pages.top) {
            is Page.Categories -> {
                if (p.focus == 0) return openCamera()
                val c = focusedCategory() ?: return
                pages = pages.push(Page.Thumbnails(c.source, c.title))
                render()
            }
            is Page.Thumbnails -> when {
                p.focus == -1 -> openCamera()
                selecting -> shown.getOrNull(p.focus)?.let(::select)
                else -> view(p, p.focus, slideshow = false)
            }
        }
    }

    /** Select Picture: [p] to the screen that asked for it, which may read it, and Pictures closes. */
    private fun select(p: Picture) {
        if (p.id < 0) return // made-up picture
        setResult(RESULT_OK, Intent().setData(Store.uri(p.id)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    private fun openCamera() {
        if (!startSafely(camera())) status(this, getString(R.string.no_camera))
    }

    private fun view(p: Page.Thumbnails, index: Int, slideshow: Boolean) {
        if (shown.isEmpty()) return
        startActivity(ViewerActivity.intent(this, p.source, p.title, index, slideshow))
    }

    private fun act(a: Act) {
        val page = pages.top
        val picture = focusedPicture()
        when (a) {
            Act.OPEN -> open()
            Act.VIEW_SLIDESHOW -> when (page) {
                is Page.Categories -> focusedCategory()?.takeIf { it.pictures.isNotEmpty() }?.let {
                    startActivity(ViewerActivity.intent(this, it.source, it.title, 0, slideshow = true))
                }
                is Page.Thumbnails -> view(page, page.focus.coerceAtLeast(0), slideshow = true)
            }
            Act.DELETE -> picture?.let(acts::delete)
            Act.SET_WALLPAPER -> picture?.let(acts::setWallpaper)
            Act.ASSIGN_CONTACT -> picture?.let(acts::assignToContact)
            Act.SEND -> show(sendMenu())
            Act.SEND_TEXT -> picture?.let(acts::sendAsText)
            Act.HELP -> dev.bold7.ui.Help.open(this, "pictures")
            Act.CLOSE -> finish()
            Act.SWITCH -> SwitchApplication.start(this)
            Act.ROTATE, Act.ZOOM -> Unit
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!acts.onResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    // Menus

    private fun fullMenu(): Menu<Act>? = when (val p = pages.top) {
        is Page.Categories -> if (p.focus == 0) null else categoriesMenu()
        is Page.Thumbnails -> if (focusedPicture() == null) null else gridMenu(shown.size)
    }

    private fun shortMenu(): Menu<Act>? = when (val p = pages.top) {
        is Page.Categories -> if (p.focus == 0) null else categoriesShortMenu(p.focus)
        is Page.Thumbnails -> if (focusedPicture() == null) null else gridShortMenu(p.focus)
    }

    private fun label(a: Act) = getString(when (a) {
        Act.OPEN -> R.string.act_open
        Act.VIEW_SLIDESHOW -> R.string.act_view_slideshow
        Act.DELETE -> R.string.act_delete
        Act.SET_WALLPAPER -> R.string.act_set_wallpaper
        Act.ASSIGN_CONTACT -> R.string.act_assign_contact
        Act.SEND -> if (menu?.short == true) R.string.act_send_short else R.string.act_send
        Act.SEND_TEXT -> R.string.act_send_text
        Act.ROTATE -> R.string.act_rotate
        Act.ZOOM -> R.string.act_zoom
        Act.HELP -> R.string.act_help
        Act.CLOSE -> R.string.act_close
        Act.SWITCH -> R.string.act_switch_application
    })

    private fun show(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val m = menu ?: return
        val a = m.entries.getOrNull(index)?.action ?: return
        if (a == Act.SEND) return act(a)
        show(null)
        act(a)
    }

    // Keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (e.action == KeyEvent.ACTION_UP) {
            if (!click) return super.dispatchKeyEvent(e)
            if (held || menuClick) { held = false; menuClick = false } else if (menu == null) open()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            // The click's release then belongs to the menu, not to the screen under it (it would open or zoom).
            if (click) menuClick = true
            when (val r = m.key(
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                    KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                    else -> if (click) MenuKey.CLICK else return true
                },
            )) {
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> show(null)
            }
            return true
        }
        when {
            code == KeyEvent.KEYCODE_DPAD_UP -> step(Step.UP)
            code == KeyEvent.KEYCODE_DPAD_DOWN -> step(Step.DOWN)
            code == KeyEvent.KEYCODE_DPAD_LEFT -> step(Step.LEFT)
            code == KeyEvent.KEYCODE_DPAD_RIGHT -> step(Step.RIGHT)
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE -> {
                pages = pages.pop() ?: return super.dispatchKeyEvent(e)
                render()
            }
            code == KeyEvent.KEYCODE_MENU -> show(fullMenu())
            click -> if (e.isLongPress || e.repeatCount == 1) {
                held = true
                show(shortMenu())
            }
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    // Thumbnails, decoded off the main thread, kept in the graph's bounded cache.

    private fun thumbnail(p: Picture, side: Int): Bitmap? {
        val g = graph
        g.thumbnails.get(p.id)?.let { return it }
        if (loading.add(p.id)) {
            g.decoder.execute {
                val b = g.store.thumbnail(p, side)
                g.main.post {
                    loading.remove(p.id)
                    if (b != null) g.thumbnails.put(p.id, b) else noThumb?.let { g.thumbnails.put(p.id, it) }
                    if (pages.top is Page.Thumbnails) grid.invalidateViews() else list.invalidateViews()
                }
            }
        }
        return null
    }

    private inner class CategoryRows : BaseAdapter() {
        override fun getCount() = cats.size + 1
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView as? CategoryRowView ?: CategoryRowView(this@PicturesActivity, camera = position == 0)
            val lit = position == (pages.top as? Page.Categories)?.focus
            if (position == 0) {
                v.bind(getString(R.string.camera), "", lit, null, cameraIcon)
            } else {
                val c = cats[position - 1]
                val first = c.pictures.firstOrNull()?.let { thumbnail(it, cell - 2 * ThumbView.INSET) }
                v.bind(c.title, items(c.pictures.size), lit, first, noPicture)
            }
            return v
        }
    }

    private inner class Cells : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].id
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView as? ThumbView ?: ThumbView(this@PicturesActivity, cell)
            val lit = position == (pages.top as? Page.Thumbnails)?.focus
            v.bind(thumbnail(shown[position], cell - 2 * ThumbView.INSET), lit)
            return v
        }
    }
}
