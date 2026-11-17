package dev.bold7.help

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import dev.bold7.design.ListColors
import dev.bold7.platform.enableStrictModeIfDebuggable
import dev.bold7.platform.hideStatusBar
import dev.bold7.platform.speakOs7Language
import dev.bold7.ui.Help
import dev.bold7.ui.LineItemView
import dev.bold7.ui.ListHeaderView
import dev.bold7.ui.ListItemView
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SearchField
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.TitleBar
import dev.bold7.ui.bb10List
import dev.bold7.ui.status
import dev.bold7.ui.withSwitchApplication
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Where Help reads: the 9900's help the module mounts (decoded at install, core/firmware), else,
 * for the development build only, the same tree pushed into the app's own external folder; and
 * Bold7's section from the app's assets. Nothing else is ever opened: a path that would leave
 * these is refused.
 */
class HelpFiles(private val context: Context, private val viewport: String) {
    /** Looked up on first use, off the main thread (the reads of a page, the topic). */
    val root: File? by lazy {
        listOf(File("/product/media/bold7/help"), context.getExternalFilesDir("help"))
            .firstOrNull { it != null && File(it, "index.html").isFile }
    }

    /** The bytes of [path] (relative to the help), prepared if it is a page; null if there is no such file. */
    fun open(path: String): InputStream? {
        if (path.startsWith("bold7/")) {
            val bytes = try { context.assets.open(path).use { it.readBytes() } } catch (e: java.io.IOException) { return null }
            return if (path.endsWith(".html")) HelpPages.prepare(String(bytes), path, viewport).byteInputStream() else bytes.inputStream()
        }
        if (path == "index.html" && root == null) return HelpPages.prepare(HelpPages.contentsWithoutRim(), path, viewport).byteInputStream()
        val dir = root ?: return null
        val f = File(dir, path).canonicalFile
        if (!f.path.startsWith(dir.canonicalPath + File.separator) || !f.isFile) return null
        if (!path.endsWith(".html")) return f.inputStream()
        return ByteArrayInputStream(HelpPages.prepare(f.readText(), path, viewport).toByteArray())
    }

    fun text(path: String): String? = root?.let { File(it, path) }?.takeIf { it.isFile }?.readText()
}

/**
 * Help of BBOS 7.1 (docs/BBOS7-VISUEL.md §39): the title "Help", the Search field (sim-440, C), then
 * a page of the 9900's help, or, once something is typed, the search's list (sim-444, C). The pages
 * are shown as the 9900 shows them, by a web engine with their own style sheet (its Help is a
 * browser field): a WebView, locked to the help's folder (no JavaScript, no network, no file or
 * content access; every request answered from [HelpFiles], every link outside refused). The trackpad
 * moves between the links, the click follows one, Back goes back a page.
 */
class HelpActivity : Activity() {
    private enum class Act { CONTENTS, CLOSE, SWITCH }

    private lateinit var files: HelpFiles
    private lateinit var web: WebView
    private lateinit var field: SearchField
    private lateinit var list: ListView
    private lateinit var menuView: MenuView<Act>
    private var menu: Menu<Act>? = null
    private var screen = HelpScreen()
    private var search: HelpSearch? = null
    private val adapter = Rows()
    private val io get() = (application as HelpApp).io

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One CSS pixel to one pixel of the screen, as the 9900's browser field showed these pages
        // (their viewport: target-densitydpi=device-dpi); the Q25's pixel is the 9900's (README, Fidelity).
        val m = resources.displayMetrics
        // (Checked on the Q25: the theme's 60 px icons show 60 px wide, as on the 9900.)
        val scale = 1f / m.density
        files = HelpFiles(this, "width=${m.widthPixels}, initial-scale=$scale, maximum-scale=$scale, user-scalable=no")
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        field = SearchField(this, getString(R.string.search)).apply { isFocusable = false }
        web = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                domStorageEnabled = false
                databaseEnabled = false
                setGeolocationEnabled(false)
                safeBrowsingEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                textZoom = 100
                builtInZoomControls = false
                useWideViewPort = true
            }
            isHapticFeedbackEnabled = false
            webViewClient = Locked()
        }
        list = bb10List(ListView(this), 0).apply {
            adapter = this@HelpActivity.adapter
            isFocusable = false
            visibility = View.GONE
            setOnItemClickListener { _, _, position, _ -> set(screen.copy(focus = position)); open() }
        }
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = ::chosen, onExpand = { menu?.let { show(it.expand()) } }, onClose = { show(null) })
        window.hideStatusBar()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(ListColors.Background)
            addView(LinearLayout(this@HelpActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TitleBar(this@HelpActivity, getString(R.string.title)), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
                addView(field, LinearLayout.LayoutParams(match, SearchField.HEIGHT))
                addView(FrameLayout(this@HelpActivity).apply {
                    addView(web, FrameLayout.LayoutParams(match, match))
                    addView(list, FrameLayout.LayoutParams(match, match))
                }, LinearLayout.LayoutParams(match, 0, 1f))
            })
            addView(menuView)
        })
        openTopic(intent)
        io.post {
            val s = HelpSearch(files.text("searchContent.txt").orEmpty())
            runOnUiThread { search = s }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        set(HelpScreen())
        openTopic(intent)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /** The page of the topic the app named (Help of its menu), else the contents. */
    private fun openTopic(intent: Intent?) {
        val topic = intent?.getStringExtra(Help.EXTRA_TOPIC)
        io.post {
            val page = files.text("index.html")?.let { HelpPages.topicPage(it, topic) } ?: "index.html"
            runOnUiThread {
                web.clearHistory()
                web.loadUrl(BASE + page)
                web.requestFocus()
            }
        }
    }

    private inner class Locked : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.host == HOST) return false
            status(this@HelpActivity, getString(R.string.outside))
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val url = request.url
            val stream = (if (url.host == HOST) url.path?.removePrefix("/")?.let(files::open) else null)
                ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            return WebResourceResponse(mime(url), if (mime(url).startsWith("text/")) "utf-8" else null, stream)
        }

        private fun mime(url: Uri) = when (url.path.orEmpty().substringAfterLast('.').lowercase()) {
            "html" -> "text/html"
            "css" -> "text/css"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
    }

    // The search

    private fun set(next: HelpScreen) {
        val was = screen
        screen = next
        if (next.query != was.query) field.setText(next.query)
        list.visibility = if (next.searching) View.VISIBLE else View.GONE
        web.visibility = if (next.searching) View.INVISIBLE else View.VISIBLE
        adapter.notifyDataSetChanged()
        // New words: the list from its top band; else the focused row kept in view.
        if (next.query != was.query) list.setSelection(0)
        else if (next.searching && (next.focus < list.firstVisiblePosition || next.focus > list.lastVisiblePosition)) list.setSelection(next.focus)
        if (!next.searching && was.searching) web.requestFocus()
    }

    private fun find(q: String) = search?.find(q).orEmpty()

    private fun open() {
        when (val r = screen.rows.getOrNull(screen.focus)) {
            is Result -> {
                set(HelpScreen())
                web.loadUrl(BASE + r.found.page)
            }
            // Extended Search, Device: the words to the home's search (D: the home searches the
            // phone as the 9900's universal search does).
            Device -> startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> Unit
        }
    }

    // The menu (D)

    private fun label(a: Act) = getString(when (a) {
        Act.CONTENTS -> R.string.act_contents
        Act.CLOSE -> R.string.act_close
        Act.SWITCH -> R.string.act_switch_application
    })

    private fun show(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val a = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        when (a) {
            Act.CONTENTS -> {
                set(HelpScreen())
                web.loadUrl(BASE + "index.html")
            }
            Act.CLOSE -> finish()
            Act.SWITCH -> SwitchApplication.start(this)
        }
    }

    // Keys: letters to the search, the trackpad to the list or to the page's links.

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val m = menu
        if (m != null) {
            if (e.action != KeyEvent.ACTION_DOWN) return true
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
        if (code == KeyEvent.KEYCODE_MENU) {
            if (e.action == KeyEvent.ACTION_DOWN) show(Menu(listOf(MenuEntry(Act.CONTENTS), MenuEntry(Act.CLOSE)), highlighted = 0).withSwitchApplication(Act.SWITCH))
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (e.action != KeyEvent.ACTION_DOWN) return true
            val back = screen.back()
            when {
                back != null -> set(back)
                web.canGoBack() -> web.goBack()
                else -> finish()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            if (e.action == KeyEvent.ACTION_DOWN) set(screen.erased(::find))
            return true
        }
        if (screen.searching) {
            if (e.action != KeyEvent.ACTION_DOWN) return true
            when {
                code == KeyEvent.KEYCODE_DPAD_UP -> set(screen.step(down = false))
                code == KeyEvent.KEYCODE_DPAD_DOWN -> set(screen.step(down = true))
                click -> open()
                else -> typed(e)
            }
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN && !click && code !in ARROWS && typed(e)) return true
        return super.dispatchKeyEvent(e)
    }

    private fun typed(e: KeyEvent): Boolean {
        val c = e.getUnicodeChar(e.metaState)
        if (c <= 0 || Character.isISOControl(c)) return false
        if (e.repeatCount == 0 && !(c == ' '.code && !screen.searching)) set(screen.typed(c.toChar(), ::find))
        return true
    }

    private inner class Rows : BaseAdapter() {
        override fun getCount() = screen.rows.size
        override fun getItem(position: Int) = screen.rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when (screen.rows[position]) {
            is Band -> 0
            is Result -> 1
            Device -> 2
        }

        override fun isEnabled(position: Int) = screen.rows[position] !is Band

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val lit = position == screen.focus
            return when (val r = screen.rows[position]) {
                Band.RESULTS -> (convertView as? ListHeaderView ?: ListHeaderView(this@HelpActivity)).apply { show(getString(R.string.results, screen.found)) }
                Band.EXTENDED -> (convertView as? ListHeaderView ?: ListHeaderView(this@HelpActivity)).apply { show(getString(R.string.extended)) }
                is Result -> (convertView as? LineItemView ?: LineItemView(this@HelpActivity)).apply { bind(r.found.title, lit) }
                Device -> (convertView as? ListItemView ?: ListItemView(this@HelpActivity)).apply {
                    bind(getString(R.string.device), getString(R.string.device_search, screen.query), lit)
                }
            }
        }
    }

    private companion object {
        const val HOST = "help.bold7.invalid"
        const val BASE = "https://$HOST/"
        val ARROWS = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    }
}
