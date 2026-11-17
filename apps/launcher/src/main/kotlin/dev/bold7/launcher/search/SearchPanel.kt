package dev.bold7.launcher.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextPaint
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.bold7.design.HomeDimens
import dev.bold7.design.Typography
import dev.bold7.launcher.R
import dev.bold7.launcher.home.FocusPatch
import dev.bold7.launcher.home.App
import dev.bold7.launcher.options.OptionsActivity
import dev.bold7.launcher.quickstep.WindowZoom
import dev.bold7.platform.RemoteLaunches
import dev.bold7.showcase.showcaseOn
import dev.bold7.ui.Dir
import dev.bold7.ui.EmoticonPen
import dev.bold7.ui.EmoticonStrip
import dev.bold7.ui.Emoticons
import dev.bold7.ui.Travel
import dev.bold7.ui.emoticonChunks
import dev.bold7.ui.emoticonWidth
import dev.bold7.ui.fit
import dev.bold7.ui.slate
import dev.bold7.ui.themeBitmap
import dev.bold7.ui.themeImage
import dev.bold7.ui.useCaret
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Bold7/Search"

/**
 * The universal search of OS 7.1, in pixels of the 9900 kept on the Q25 (docs/BBOS7-VISUEL.md, § 9):
 * sim-54 to sim-56 (C) measure the field, the tiles and their text; what they do not show is D.
 */
private object Os7 {
    const val FIELD_TOP = 3 // the white field, y 96 to 134 under the banner's 93 (sim-54, C)
    const val FIELD = 38
    const val FIELD_LEFT = 2
    const val MIC = 43 // banner_voice_button.png, at the right of the row (sim-54, C)
    const val MIC_RIGHT = 5
    const val DIAL = 47 // « Dial: » row, its line at y 183 (sim-54, C)
    const val TILE = 134 // a tile, y 142 to 276 on sim-56 (C)
    const val TILE_ICON = 92 // the firmware's icon at its own size, its top on the tile's (sim-54, sim-56, C: the wrench is 61 px wide)
    const val TILE_ICON_TOP = 0
    const val LABEL = 17 // the tile's name: D, to the width of « Social Feeds » (93 px, sim-54)
    const val LABEL_ONE = 119 // baseline of a name alone, of a name over its count (sim-56, C)
    const val LABEL_TWO = 109
    const val COUNT = 130
    const val BAR = 35 // « Web Suggestions from Bing », « Extended Search » (sim-56, C)
    const val ROW = 72 // a result of a category taken: the Contacts list's (BBOS7-VISUEL § 15, D here)
    const val ROW_ICON = 67
    const val HEADER = 47
    const val DIM = 0x8C000000.toInt() // the wallpaper under the results, darkened (D, to the eye)
    const val DIAL_BAND = 0x66000000
    const val LINE = 0xFF5A5A5A.toInt()
    const val FIELD_BORDER = 0xFF7A7A7A.toInt() // border_homescreen_search.png: a 9x9 white box (A), drawn
    const val WHITE = 0xFFFFFFFF.toInt()
    const val DETAIL = 0xFFC8C8C8.toInt()
}

/** The search's images, decoded off the main thread. */
private class Images(private val activity: Activity) {
    private fun decode(name: String): Bitmap = themeBitmap(activity, name)

    /** home_focus.9.png: the focus of the icons, stretched to the tile (A). */
    val focus = FocusPatch(themeImage(activity, "home_focus"))
    val mic = decode("home_banner_voice_button")
    val row = decode("home_indicator_bar_background")

    /** The icon of the app that stands for a category: the tile and the rows of its list. */
    val categories = mapOf(
        Category.CONTACTS to decode("bbos7_contacts"),
        Category.CALLS to decode("bbos7_phone"),
        Category.DOCUMENTS to decode("bbos7_files"),
        Category.SETTINGS to decode("bbos7_options"),
        Category.MUSIC to decode("bbos7_music"),
        Category.VIDEOS to decode("bbos7_camera"), // no Videos icon in the theme: D
        Category.PICTURES to decode("bbos7_pictures"),
        Category.CALENDAR to decode("bbos7_calendar"),
        Category.TEXT_MESSAGES to decode("bbos7_text_messages"),
    )
}

private fun textPaint(size: Int, weight: Int, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size.toFloat()
    typeface = slate(weight)
    this.color = color
}

/** The advance of [s] in [paint], the matched [ranges] in [bold], its emojis counted as OS 7's emoticon images. */
internal fun matchedWidth(s: String, ranges: List<IntRange>, paint: Paint, bold: Paint): Float {
    var w = paint.measureText(s)
    for (f in Emoticons.find(s, shortcuts = false)) w += emoticonWidth(paint.textSize) - paint.measureText(s, f.start, f.end)
    for (r in ranges) if (r.first < s.length) {
        val end = minOf(r.last + 1, s.length)
        w += bold.measureText(s, r.first, end) - paint.measureText(s, r.first, end)
    }
    return w
}

/** [text] cut to [room] by the one `fit` (core/ui), the matched [ranges] measured in bold. */
internal fun fitMatched(text: String, ranges: List<IntRange>, room: Float, paint: Paint, bold: Paint): String =
    fit(text, room) { matchedWidth(it, ranges, paint, bold) }

private val emoticonPen = EmoticonPen()

/** [s] from [a] to [b] at ([x], [baseline]), its emojis as images; where it ends. */
private fun Canvas.drawRun(s: String, a: Int, b: Int, x: Float, baseline: Float, paint: Paint): Float =
    emoticonPen.draw(this, emoticonChunks(s.substring(a, b)), x, baseline, paint)

/** [text] at ([x], [baseline]) cut to [width], the [ranges] the query matched in [bold] (sim-56: « Opt » bold). */
private fun Canvas.drawMatched(text: String, ranges: List<IntRange>, x: Float, baseline: Float, width: Float, paint: TextPaint, bold: TextPaint) {
    val shown = fitMatched(text, ranges, width, paint, bold)
    var at = x
    var i = 0
    for (r in ranges.filter { it.first < shown.length } + listOf(shown.length..shown.length)) {
        if (r.first > i) at = drawRun(shown, i, r.first, at, baseline, paint)
        val end = minOf(r.last + 1, shown.length)
        if (end > r.first) at = drawRun(shown, r.first, end, at, baseline, bold)
        i = maxOf(i, end)
    }
}

/** [text] centred on [cx], the matched [ranges] in bold. */
private fun Canvas.drawMatchedCentred(text: String, ranges: List<IntRange>, cx: Float, baseline: Float, width: Float, paint: TextPaint, bold: TextPaint) {
    val shown = fitMatched(text, ranges, width, paint, bold)
    val w = matchedWidth(shown, ranges, paint, bold)
    drawMatched(shown, ranges, cx - w / 2, baseline, width, paint, bold)
}

/** The categories a source of the device fills, read in the order they show. */
private val SEARCHED = listOf(
    Category.CONTACTS, Category.CALLS, Category.DOCUMENTS, Category.MUSIC,
    Category.VIDEOS, Category.PICTURES, Category.CALENDAR, Category.TEXT_MESSAGES,
)

/**
 * The universal search typed from the home, as OS 7.1 shows it: the field in the
 * row of the indicators, under the banner, then one grid of tiles, six to a row (the apps found,
 * then one tile for each other category with its count), and « Extended Search » at the bottom.
 * Over the home in its window, so that no key typed is lost on the way. Built on the first search,
 * then only hidden: closed, it draws and listens to nothing. The apps come from the home, the
 * rest from Android's providers on [work], read again at each key; settings by intent; nothing
 * goes to the network unless « Extended Search » or the microphone is taken. No permission is ever
 * asked here: tools/install.sh grants them once, a category without its permission stays empty.
 */
internal class SearchPanel(
    private val activity: Activity,
    private val apps: () -> List<App>,
    private val icon: (App) -> Bitmap?,
    /** The BBOS 7 icon an app wears, if any (bbos7_icons.tsv): its keywords find it. */
    private val tableIcon: (App) -> String?,
    /** Android's name of an app bbos7_apps.tsv renamed, null for the others: a hidden keyword. */
    private val androidName: (App) -> String?,
    /** The sources' thread. */
    private val work: CoroutineDispatcher,
    /** The pictures' (glyphs, contact pictures, thumbnails): never in a source's way. */
    private val pictures: CoroutineDispatcher,
    private val scope: CoroutineScope,
    /** The apps open as from the grid; the other intents as Android opens them. */
    private val launches: RemoteLaunches,
    private val onClose: () -> Unit,
) : FrameLayout(activity) {
    private val sources = Sources(activity)
    private var images: Images? = null
    private val thumbs = LruCache<String, Bitmap>(64) // contact pictures and media thumbnails
    private val decoding = HashMap<String, Job>() // the pictures being decoded, by key
    private val settings = settingsPages(activity)
    private val settingsFolded = settings.map { Folded(it.label) }
    private val settingsKeywords = settings.map { s -> s.keywords.map(::Folded) }

    private val field = EditText(activity)
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(activity)
    private val web = WebBar()

    private var text = ""
    private var filter: Category? = null
    private var page = Page(emptyList(), emptyList())
    private var selected = 0
    private var moved = false // the trackpad moved the focus since the last query
    private var travel = Travel()
    private var held = false // the trackpad's click is down
    private val found = HashMap<Category, Pair<String, Found>>() // what each source found, for which term
    private var finding: Job? = null
    private var cancel: CancellationSignal? = null
    private var foldedApps: Triple<List<App>, List<Folded>, List<List<Folded>>>? = null // the home's apps, folded once per list
    private var loading: Job? = null // the glyphs
    private var rendering = false // a render waits for the next frame

    val isOpen get() = visibility == VISIBLE

    init {
        visibility = GONE
        setWillNotDraw(false)
        isClickable = true // what is under it, the home, takes no touch
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        field.apply {
            // The white box of sim-54 (border_homescreen_search.png), the text black, OS 7's caret.
            background = GradientDrawable().apply {
                setColor(Os7.WHITE)
                setStroke(1, Os7.FIELD_BORDER)
                cornerRadius = 3f
            }
            typeface = slate(Typography.REGULAR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.TEXT.toFloat())
            setTextColor(0xFF000000.toInt())
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(5, 0, 5, 0)
            useCaret()
            isSingleLine = true
            // No capitals, corrections or suggestions (sim-54 types « o », « opt »); Enter is Go.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // What is searched is not for the keyboard to learn.
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) = if (isOpen) query(s.toString()) else Unit
            })
            setOnKeyListener { _, code, e -> key(code, e) }
            // Enter, when the input method takes it before the field.
            setOnEditorActionListener { _, id, _ ->
                (id == EditorInfo.IME_ACTION_GO).also { if (it) enter() }
            }
            // The trackpad stays captured by the window: its moves come to the focused field.
            setOnCapturedPointerListener { _, e -> pointer(e) }
        }
        scroll.isFocusable = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(list)
        val top = HomeDimens.BANNER + HomeDimens.INDICATOR_ROW
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { topMargin = top; bottomMargin = Os7.BAR })
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, Os7.BAR, Gravity.BOTTOM))
        // The width of the row less the microphone, by margins: sized in onSizeChanged, the field
        // stayed 0 px wide on the Q25 under Lineage (a layout asked for during the layout that
        // makes the panel visible is lost), and a tap meant for it fell on the microphone.
        addView(field, LayoutParams(LayoutParams.MATCH_PARENT, Os7.FIELD).apply {
            topMargin = HomeDimens.BANNER + Os7.FIELD_TOP
            leftMargin = Os7.FIELD_LEFT
            rightMargin = Os7.MIC + Os7.MIC_RIGHT + 4
        })
        addView(MicView(), LayoutParams(Os7.MIC, Os7.MIC).apply { gravity = Gravity.END; topMargin = HomeDimens.BANNER + 2; rightMargin = Os7.MIC_RIGHT })
    }

    // Under the banner (which stays, sim-54): the row of the indicators, the wallpaper darkened.
    override fun onDraw(canvas: Canvas) {
        val top = HomeDimens.BANNER.toFloat()
        val row = images?.row
        if (row != null) canvas.drawBitmap(row, null, RectF(0f, top, width.toFloat(), top + HomeDimens.INDICATOR_ROW), null)
        val p = Paint().apply { color = Os7.DIM }
        canvas.drawRect(0f, top + HomeDimens.INDICATOR_ROW, width.toFloat(), height.toFloat(), p)
    }

    /** The glyphs, decoded at each opening and let go at the closing. */
    private fun loadImages() {
        if (images != null || loading?.isActive == true) return
        loading = scope.launch {
            val loaded = withContext(pictures) { Images(activity) }
            images = loaded
            invalidateAll()
        }
    }

    private fun invalidateAll() {
        for (i in 0 until list.childCount) list.getChildAt(i).invalidate()
        for (i in 0 until childCount) getChildAt(i).invalidate()
    }

    /** Opens with what was typed on the home (or said to the microphone), the cursor after it. */
    fun open(typed: String) {
        filter = null
        held = false
        travel = Travel()
        loadImages()
        visibility = VISIBLE
        field.setText(typed) // the watcher runs the query
        field.setSelection(field.length())
        field.requestFocus()
    }

    fun close() {
        if (!isOpen) return
        visibility = GONE
        cancel?.cancel()
        finding?.cancel()
        decoding.values.forEach { it.cancel() }
        decoding.clear()
        field.text.clear()
        field.clearFocus()
        list.removeAllViews()
        // Nothing of what was searched stays in memory.
        found.clear()
        thumbs.evictAll()
        page = Page(emptyList(), emptyList())
        text = ""
        scope.launch { withContext(work) { sources.forget() } }
        loading?.cancel()
        images = null
    }

    /** Back: the category taken, then the search itself. */
    fun back() {
        if (filter != null) filter(null) else onClose()
    }

    private fun query(typed: String) {
        text = typed
        selected = 0
        moved = false
        travel = Travel()
        cancel?.cancel()
        finding?.cancel()
        val term = typed.trim()
        val todo = SEARCHED.filter { term.isNotBlank() && found[it]?.first != term }
        if (todo.isNotEmpty()) {
            val signal = CancellationSignal().also { cancel = it }
            finding = scope.launch {
                for (category in todo) {
                    val result = withContext(work) {
                        try {
                            sources.find(category, term, signal)
                        } catch (e: OperationCanceledException) {
                            null // a newer query is on its way
                        } catch (e: RuntimeException) { // the provider's: this category empty, the search goes on
                            // Its message only when it names nothing typed (the query's own SQL, a grammar).
                            Log.w(TAG, "$category unreadable: ${e.javaClass.simpleName}" + loggable(e.message, term).let { if (it == null) "" else ": $it" })
                            Found(emptyList(), 0)
                        }
                    } ?: return@launch
                    found[category] = term to result
                    renderSoon()
                }
            }
        }
        render(keep = false)
    }

    private fun date(millis: Long, time: Boolean) =
        DateUtils.formatDateTime(activity, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or if (time) DateUtils.FORMAT_SHOW_TIME else 0)

    /** A result of a source, its title and its second line, what the query matched in both. */
    private fun hit(item: Any, query: String): Hit {
        fun of(title: String, detail: String) = Hit(item, title, matchRanges(title, query).orEmpty(), detail, matchRanges(detail, query).orEmpty())
        return when (item) {
            is Contact -> Hit(item, item.name, matchRanges(item.name, query).orEmpty())
            is Call -> of(
                item.name ?: item.number,
                activity.getString(
                    when (item.type) {
                        CallLog.Calls.OUTGOING_TYPE -> R.string.search_call_out
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> R.string.search_call_missed
                        else -> R.string.search_call_in
                    },
                ) + ", " + date(item.date, time = true),
            )
            is Message -> of(item.name ?: item.address, item.body.replace('\n', ' '))
            is Event -> of(item.title, listOf(date(item.begin, time = !item.allDay), item.location).filter(String::isNotBlank).joinToString(", "))
            is Media -> of(item.title, item.detail.ifEmpty { date(item.date, time = false) })
            else -> Hit(item, item.toString(), emptyList())
        }
    }

    private fun sections(): List<Section> {
        if (text.isBlank()) return emptyList()
        val term = text.trim()
        val sourced = SEARCHED.map { category ->
            // Those of an earlier term stay until the new ones come, highlighted for this one.
            val f = found[category]?.second
            // Only what the page shows becomes a row: the rest is counted.
            Section(category, f?.items.orEmpty().take(if (filter == category) MAX_RESULTS else 1).map { hit(it, term) }, f?.total ?: 0)
        }
        // A tile of a category counts what it found; the settings pages, all of them, are counted here.
        val pages = hits(settings, settingsFolded, text, settingsKeywords::get)
        return sourced + listOf(
            Section(Category.APPS, appHits()),
            Section(Category.SETTINGS, pages),
            Section(Category.WEB, listOf(Hit(WebSearch, activity.getString(R.string.search_extended), emptyList(), term))),
        )
    }

    private fun appHits(): List<Hit> {
        val all = apps()
        val (_, folded, keywords) = foldedApps?.takeIf { it.first === all }
            ?: Triple(all, all.map { Folded(it.label) }, all.map { a -> appAliases(tableIcon(a), androidName(a)).map(::Folded) }).also { foldedApps = it }
        return hits(all, folded, text, keywords::get)
    }

    /** Sources arriving together are drawn once, at the next frame. */
    private fun renderSoon() {
        if (rendering) return
        rendering = true
        postOnAnimation {
            rendering = false
            if (isOpen) render(keep = true)
        }
    }

    /** The results again; with [keep], the focus stays on its target if it is still there. */
    private fun render(keep: Boolean) {
        val was = page.targets.getOrNull(selected)
        page = page(sections(), filter, dial = text.trim().takeIf { phoneLike(it) })
        selected = if (keep) reselect(page, was) else 0
        list.removeAllViews()
        for (row in page.rows) {
            when {
                row is Row.Header -> list.addView(HeaderView(row))
                row is Row.Item && row.hit.item === WebSearch -> web.row = row
                row is Row.Item && row.hit.item is Dial -> list.addView(DialView(row))
                row is Row.Item -> list.addView(ItemView(row))
                row is Row.Apps -> list.addView(TilesView(row))
            }
        }
        if (page.rows.none { it is Row.Item && it.hit.item === WebSearch }) web.row = null
        web.visibility = if (web.row == null) GONE else VISIBLE
        scroll.layoutParams = (scroll.layoutParams as LayoutParams).apply { bottomMargin = if (web.row == null) 0 else Os7.BAR }
        web.invalidate()
    }

    private fun filter(category: Category?) {
        filter = category
        selected = 0
        moved = false
        render(keep = false)
        scroll.scrollTo(0, 0)
    }

    private fun key(code: Int, e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(Dir.DOWN)
            // Along a row of tiles; elsewhere, the field's cursor.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (code == KeyEvent.KEYCODE_DPAD_LEFT) Dir.LEFT else Dir.RIGHT
                if (step(page, selected, dir) == selected) return false
                if (down) move(dir)
            }
            // The release acts; the top result, the soft focus, by default.
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER ->
                if (!down && !e.isCanceled) enter()
            // The Call key dials what is typed when it is a number (the « Dial: » row), else opens the phone.
            KeyEvent.KEYCODE_CALL -> if (!down && !e.isCanceled) dial(text.trim().takeIf { phoneLike(it) })
            else -> return false
        }
        return true
    }

    private fun pointer(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // A captured click comes as a down and a button press, then an up and a release: once.
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> held = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> if (held) {
                held = false
                enter()
            }
            MotionEvent.ACTION_CANCEL -> held = false
            MotionEvent.ACTION_MOVE -> {
                var dx = e.x
                var dy = e.y
                for (i in 0 until e.historySize) {
                    dx += e.getHistoricalX(i)
                    dy += e.getHistoricalY(i)
                }
                val (next, steps) = travel.moved(dx, dy)
                travel = next
                steps.forEach(::move)
            }
        }
        return true
    }


    private fun enter() {
        entered(page, selected, moved)?.let(::activate)
    }

    private fun move(dir: Dir) {
        if (page.targets.isEmpty()) return
        selected = step(page, selected, dir)
        moved = true
        for (i in 0 until list.childCount) list.getChildAt(i).invalidate()
        web.invalidate()
        // The row of the focus into view.
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            if (v is Target && v.holds(selected)) {
                val top = v.top - scroll.scrollY
                if (top < 0) scroll.smoothScrollTo(0, v.top)
                else if (top + v.height > scroll.height) scroll.smoothScrollTo(0, v.bottom - scroll.height)
                break
            }
        }
    }

    private fun activate(hit: Hit) {
        // The showcase's made-up results name nothing a real app could open.
        if ((hit.item is Contact || hit.item is Call || hit.item is Message || hit.item is Event || hit.item is Media) && showcaseOn(activity)) return
        when (val item = hit.item) {
            is Category -> filter(item)
            is Dial -> dial(item.number)
            is App -> start(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(item.packageName, item.className))
                    .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
            // Content URIs the launcher may read, lent to the app that opens them (StrictMode kills
            // a debug build that hands one over without it).
            is Contact -> start(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(item.id, item.lookupKey)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            is Call -> start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", item.number, null)))
            // The conversation with that number, in the text messaging app.
            is Message -> start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", item.address, null)))
            is Event -> start(
                Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, item.id))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, item.begin)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            is Media -> start(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            is Setting -> start(item.screen?.let { Intent(activity, OptionsActivity::class.java).putExtra(OptionsActivity.EXTRA_SCREEN, it.name) } ?: Intent(item.action))
            WebSearch -> start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(text.trim()))).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    /** The Phone app, [number] filled in when there is one: the call itself is the Phone app's to place. */
    private fun dial(number: String?) = start(Intent(Intent.ACTION_DIAL, number?.let { Uri.fromParts("tel", it, null) }))

    /**
     * The microphone: Android's speech recognizer, taken only here. Google's may use the network
     * in spite of EXTRA_PREFER_OFFLINE. What it heard comes back to LauncherActivity, which opens
     * the search with it.
     */
    private fun listen() {
        try {
            activity.startActivityForResult(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
                REQUEST_VOICE,
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no speech recognizer: ${e.javaClass.simpleName}") // never its message: it may name a number or a file
        }
    }

    /**
     * Starts what was chosen, with OS 7's zoom as from the grid (whatever opens leaves the home,
     * OS 7's transition), and closes; refused or missing, logged, the search stays.
     */
    private fun start(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launches.start(activity, intent, WindowZoom.open)
            onClose()
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "nothing to start for ${intent.action}: ${e.javaClass.simpleName}") // never its message: it may name a number or a file
        } catch (e: SecurityException) {
            Log.w(TAG, "start refused for ${intent.action}: ${e.javaClass.simpleName}") // never its message: it may name a number or a file
        }
    }

    /**
     * The picture under [key] (a contact's, a media thumbnail), decoded once on [work] whatever
     * the rows rebuilt meanwhile: the rows read the cache as they draw.
     */
    private fun decode(key: String, load: () -> Bitmap?) {
        if (thumbs[key] != null || key in decoding) return
        decoding[key] = scope.launch {
            val bitmap = withContext(pictures) {
                try {
                    load()
                } catch (e: RuntimeException) { // refused or gone: the icon
                    Log.w(TAG, "picture unreadable: ${e.javaClass.simpleName}") // never its message: it may name a number or a file
                    null
                }
            }
            decoding.remove(key)
            if (bitmap != null) {
                thumbs.put(key, bitmap)
                for (i in 0 until list.childCount) list.getChildAt(i).invalidate()
            }
        }
    }

    private fun label(category: Category) = activity.getString(
        when (category) {
            Category.CONTACTS -> R.string.search_contacts
            Category.CALLS -> R.string.search_calls
            Category.DOCUMENTS -> R.string.search_documents
            Category.SETTINGS -> R.string.search_settings
            Category.MUSIC -> R.string.search_music
            Category.VIDEOS -> R.string.search_videos
            Category.PICTURES -> R.string.search_pictures
            Category.CALENDAR -> R.string.search_calendar
            Category.TEXT_MESSAGES -> R.string.search_text_messages
            Category.APPS, Category.WEB -> R.string.search_extended
        },
    )

    // The rows, each one view drawing itself: rebuilt at each key, a few of them.

    private interface Target {
        fun holds(target: Int): Boolean
    }

    /** The focus of a tile or a row: the theme's, stretched (sim-56, A), only once the trackpad moved. */
    private fun Canvas.focus(rect: Rect) {
        images?.focus?.draw(this, rect)
    }

    /** The category taken: its name and how many results it has (« Options (35) »). */
    private inner class HeaderView(private val row: Row.Header) : View(activity) {
        private val name = textPaint(Typography.TEXT, Typography.REGULAR, Os7.WHITE)
        private val label = "${label(row.category)} (${row.total})"

        init {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Os7.HEADER)
        }

        override fun onDraw(canvas: Canvas) {
            val p = Paint().apply { color = Os7.DIAL_BAND }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            canvas.drawText(name.fit(label, width - 24f), 12f, (height - name.descent() - name.ascent()) / 2, name)
            p.color = Os7.LINE
            canvas.drawRect(0f, height - 1f, width.toFloat(), height.toFloat(), p)
        }
    }

    /** « Dial: » and the number (sim-54); the handset key is drawn, its image not found (D). */
    private inner class DialView(private val row: Row.Item) : View(activity), Target {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = textPaint(Typography.TEXT, Typography.REGULAR, Os7.WHITE)

        init {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Os7.DIAL)
            setOnClickListener { activate(row.hit) }
        }

        override fun holds(target: Int) = target == row.target

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Os7.DIAL_BAND
            canvas.drawRect(0f, 0f, w, h, paint)
            if (selected == row.target && moved) canvas.focus(Rect(0, 0, width, height))
            val head = activity.getString(R.string.search_dial) + " "
            // The number gives way to the handset's key and the margins when it is long.
            val number = label.fit((row.hit.item as Dial).number, w - 8f - label.measureText(head) - 10f - 36f - 8f)
            val base = (h - label.descent() - label.ascent()) / 2
            canvas.drawText(head, 8f, base, label)
            val x = 8f + label.measureText(head)
            canvas.drawText(number, x, base, label)
            // The key of the handset after the number: a dark key, a white handset (sim-54).
            val kx = x + label.measureText(number) + 10
            paint.color = 0xFF2B2B2B.toInt()
            canvas.drawRoundRect(kx, h / 2 - 12, kx + 36, h / 2 + 12, 5f, 5f, paint)
            paint.color = Os7.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawPath(Path().apply { moveTo(kx + 9, h / 2 - 2); lineTo(kx + 9, h / 2 + 3); lineTo(kx + 27, h / 2 + 3); lineTo(kx + 27, h / 2 - 2) }, paint)
            paint.color = Os7.LINE
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, h - 1, w, h, paint)
        }
    }

    /**
     * A row of tiles (sim-54 to sim-56): the icon, the name under it with what matched in bold,
     * and « (35) » on a second line for a category. The theme's focus under the focused one.
     */
    private inner class TilesView(private val row: Row.Apps) : View(activity), Target {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val name = textPaint(Os7.LABEL, Typography.REGULAR, Os7.WHITE)
        private val bold = textPaint(Os7.LABEL, Typography.BOLD, Os7.WHITE)
        private var down = -1

        init {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Os7.TILE)
        }

        override fun holds(target: Int) = target in row.first until row.first + row.hits.size

        private fun column() = width / APP_COLUMNS

        override fun onDraw(canvas: Canvas) {
            val c = column()
            row.hits.forEachIndexed { i, hit ->
                val left = i * c
                if ((selected == row.first + i && moved) || down == i) canvas.focus(Rect(left, 0, left + c, height))
                val item = hit.item
                val bitmap = if (item is App) icon(item) else (item as? Category)?.let { images?.categories?.get(it) }
                val cx = left + c / 2f
                if (bitmap != null) {
                    val s = Os7.TILE_ICON.toFloat()
                    canvas.drawBitmap(bitmap, null, RectF(cx - s / 2, Os7.TILE_ICON_TOP.toFloat(), cx + s / 2, Os7.TILE_ICON_TOP + s), paint)
                }
                val title = if (item is Category) label(item) else hit.title
                val ranges = if (item is Category) emptyList() else hit.ranges
                val count = hit.count
                canvas.drawMatchedCentred(title, ranges, cx, (if (count == null) Os7.LABEL_ONE else Os7.LABEL_TWO).toFloat(), c - 6f, name, bold)
                if (count != null) {
                    val s = name.fit("($count)", c - 6f)
                    canvas.drawText(s, cx - name.measureText(s) / 2, Os7.COUNT.toFloat(), name)
                }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val at = (e.x / column()).toInt().takeIf { it in row.hits.indices && e.y in 0f..height.toFloat() } ?: -1
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> down = at
                MotionEvent.ACTION_MOVE -> if (at != down) down = -1
                MotionEvent.ACTION_UP -> {
                    val tapped = down
                    down = -1
                    if (tapped >= 0 && at == tapped) activate(row.hits[tapped])
                }
                MotionEvent.ACTION_CANCEL -> down = -1
            }
            invalidate()
            return at >= 0 || e.actionMasked != MotionEvent.ACTION_DOWN
        }
    }

    /** A result of the category taken: its picture or icon, title and second line, what matched in bold. */
    private inner class ItemView(private val row: Row.Item) : View(activity), Target {
        private val titlePaint = textPaint(Typography.TEXT, Typography.REGULAR, Os7.WHITE)
        private val boldPaint = textPaint(Typography.TEXT, Typography.BOLD, Os7.WHITE)
        private val detailPaint = textPaint(Typography.SUBTITLE, Typography.REGULAR, Os7.DETAIL)
        private val detailBold = textPaint(Typography.SUBTITLE, Typography.BOLD, Os7.DETAIL)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val item = row.hit.item
        private val pictureKey: String? = when {
            item is Contact && item.photo != null -> "c${item.id}".also { decode(it) { sources.photo(item) } }
            item is Media && item.mime.orEmpty().let { it.startsWith("image/") || it.startsWith("video/") } ->
                "m${item.uri}".also { decode(it) { sources.thumbnail(item, Os7.ROW_ICON) } }
            else -> null
        }

        init {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Os7.ROW)
            isFocusable = false // Android's focus stays in the field
            setOnClickListener { activate(row.hit) }
            EmoticonStrip.want(this, emoticonChunks(row.hit.detail)) // a message's emojis
        }

        override fun holds(target: Int) = target == row.target

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate() // pressed
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if ((selected == row.target && moved) || isPressed) canvas.focus(Rect(0, 0, width, height))
            val square = RectF(3f, (h - Os7.ROW_ICON) / 2, 3f + Os7.ROW_ICON, (h + Os7.ROW_ICON) / 2)
            val own = pictureKey?.let { thumbs[it] }
            val category = when (item) {
                is Contact -> Category.CONTACTS
                is Call -> Category.CALLS
                is Message -> Category.TEXT_MESSAGES
                is Event -> Category.CALENDAR
                is Setting -> Category.SETTINGS
                is Media -> item.mime.orEmpty().let { if (it.startsWith("audio/")) Category.MUSIC else if (it.startsWith("image/")) Category.PICTURES else if (it.startsWith("video/")) Category.VIDEOS else Category.DOCUMENTS }
                else -> null
            }
            (own ?: category?.let { images?.categories?.get(it) })?.let { canvas.drawBitmap(it, null, square, paint) }
            val x = square.right + 9
            val right = w - 9
            val hit = row.hit
            if (hit.detail.isEmpty()) {
                canvas.drawMatched(hit.title, hit.ranges, x, (h - titlePaint.descent() - titlePaint.ascent()) / 2, right - x, titlePaint, boldPaint)
            } else {
                canvas.drawMatched(hit.title, hit.ranges, x, 31f, right - x, titlePaint, boldPaint)
                canvas.drawMatched(hit.detail, hit.detailRanges, x, 63f, right - x, detailPaint, detailBold)
            }
            paint.color = Os7.LINE
            canvas.drawRect(0f, h - 1, w, h, paint)
        }
    }

    /** « Extended Search », the last row, pinned to the bottom (sim-56); the network only once taken. */
    private inner class WebBar : View(activity) {
        var row: Row.Item? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = textPaint(Typography.TEXT, Typography.REGULAR, Os7.WHITE)

        init {
            visibility = GONE
            setOnClickListener { row?.let { activate(it.hit) } }
        }

        override fun onDraw(canvas: Canvas) {
            val r = row ?: return
            paint.color = Os7.DIAL_BAND
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            if (selected == r.target && moved) canvas.focus(Rect(0, 0, width, height))
            val s = label.fit(r.hit.title, width - 24f)
            canvas.drawText(s, (width - label.measureText(s)) / 2, (height - label.descent() - label.ascent()) / 2, label)
        }
    }

    /** The microphone at the right of the row (banner_voice_button.png, sim-54); a tap listens. */
    private inner class MicView : View(activity) {
        init {
            setOnClickListener { listen() }
        }

        override fun onDraw(canvas: Canvas) {
            images?.mic?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
    }

    companion object {
        const val REQUEST_VOICE = 2
    }
}
