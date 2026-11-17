package dev.bold7.messages

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Dialog
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import android.view.KeyEvent
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import dev.bold7.design.Durations
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.ItemKind
import dev.bold7.showcase.SHOWCASE_EVENT_SETTING
import dev.bold7.showcase.showcaseOn
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.Dir
import dev.bold7.ui.ListHeaderView
import dev.bold7.ui.ListItemView
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.LetterHold
import dev.bold7.ui.MenuView
import dev.bold7.ui.SpeedDialKey
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.SearchField
import dev.bold7.ui.TitleBar
import dev.bold7.ui.Travel
import dev.bold7.ui.bb10List
import dev.bold7.ui.status
import dev.bold7.ui.themeBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bold7's Calendar (apps/calendar), which opens the Hub's invitations. */
private const val CALENDAR_PACKAGE = "dev.bold7.calendar"

/** Bold7's launcher, where Options' Messages and Email notifier is. */
private const val LAUNCHER = "dev.bold7.launcher"

/**
 * Google Messages, when its door is shut, opens in a task of its own cleared first: nothing left
 * there shows over the conversation (its consent page stayed once).
 */
private const val GOOGLE_MESSAGES_TASK = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

/**
 * Messages, the unified inbox of BBOS 7.1 (docs/BBOS7-VISUEL.md §15), on the Hub's store: date
 * fields, a row per message or conversation, the trackpad's focus (as arrows or as the relative
 * pointer it also is), the full menu (Menu key), the short menu (the click held,
 * a long touch, or a click on a date field), the inbox's shortcuts (guide 7.1 p. 52), View
 * Folder's views and a search field. Text messages open in the thread of OS 7
 * (ConversationActivity), or in Google Messages when its API is shut; missed calls and
 * notifications in their own app. The activity renders [InboxState] and runs the effects; it
 * decides nothing.
 *
 * `am start -n dev.bold7.messages/.MessagesActivity --ei rows 500`: made-up rows (tools/perf.sh).
 */
class MessagesActivity : Activity() {
    private val graph get() = (application as MessagesApp).graph
    private lateinit var scope: CoroutineScope

    private var state = InboxState()
    private var menu: Menu<Act>? = null
    private var held = false // the click held into a short menu: its release is not a click
    private var dialog: Dialog? = null
    private var asking: Job? = null // Google Messages' door being asked: a second Open waits for it
    private var travel = Travel()
    /** The search field being given the state's text: not an edit to report. */
    private var binding = false

    /** apps/hub's mail accounts, read at each start: C composes a mail when there is one, an account's view shows its address. */
    private var mail: List<MailAccountInfo> = emptyList()
    /** The showcase is shown (bold7_showcase, read off the main thread at each start): nothing is written nor composed. */
    @Volatile private var showcase = false

    private val strips = IconStrips()
    private val glyphs = arrayOfNulls<Bitmap>(Act.entries.size)

    private lateinit var root: FrameLayout
    private lateinit var listPage: View
    private lateinit var search: SearchField
    private lateinit var empty: ListHeaderView
    private lateinit var list: ListView
    private lateinit var arrows: ScrollArrows
    private lateinit var folderPage: View
    private lateinit var folders: ListView
    private lateinit var menuView: MenuView<Act>
    private val lines = LinesAdapter()
    private val folderAdapter = FoldersAdapter()

    private val observer by lazy {
        object : ContentObserver(graph.ioThread) {
            override fun onChange(selfChange: Boolean) = graph.feed.reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph.reader.demo = intent.getIntExtra(EXTRA_ROWS, 0)
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        search = SearchField(this, getString(R.string.search_hint)).apply {
            // An editor with Android's focus while the list's is on it: the input method types into
            // it; its text is the state's search.
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    if (!binding && state.search != null) update(InboxEvent.Typed(s.toString()))
                }
            })
            setOnCapturedPointerListener { _, e -> onPointer(e) }
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) update(InboxEvent.SearchOpened)
                false
            }
        }
        empty = ListHeaderView(this).apply { visibility = View.GONE }
        arrows = ScrollArrows(this)
        list = bb10List(ListView(this), 0).apply {
            adapter = lines
            isFocusable = false
            isHapticFeedbackEnabled = false
            setOnItemClickListener { _, _, position, _ ->
                update(InboxEvent.Moved(position))
                clicked()
            }
            setOnItemLongClickListener { _, _, position, _ ->
                update(InboxEvent.Moved(position))
                holdLine()
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) =
                    arrows.show(canScrollVertically(-1), canScrollVertically(1))
            })
        }
        listPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ListColors.Background)
            addView(search, LinearLayout.LayoutParams(match, SearchField.HEIGHT))
            addView(empty, LinearLayout.LayoutParams(match, ListHeaderView.HEIGHT))
            addView(FrameLayout(this@MessagesActivity).apply {
                addView(list)
                addView(arrows)
            }, LinearLayout.LayoutParams(match, 0, 1f))
        }
        folders = bb10List(ListView(this), 0).apply {
            adapter = folderAdapter
            isFocusable = false
            setOnItemClickListener { _, _, position, _ -> update(InboxEvent.Filtered(FOLDER_VIEWS[position])) }
        }
        folderPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ListColors.Background)
            visibility = View.GONE
            addView(TitleBar(this@MessagesActivity, getString(R.string.folder_title)), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
            addView(folders, LinearLayout.LayoutParams(match, 0, 1f))
        }
        menuView = MenuView(
            this,
            glyph = { glyphs[it.ordinal] },
            label = ::label,
            onEntry = ::chosen,
            onExpand = { menu?.let { show(it.expand()) } },
            onClose = { show(null) },
        )
        root = FrameLayout(this).apply {
            setBackgroundColor(ListColors.Background)
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            setOnCapturedPointerListener { _, e -> onPointer(e) }
            addView(listPage)
            addView(folderPage)
            addView(menuView)
        }
        setContentView(root)

        scope = CoroutineScope(SupervisorJob() + graph.main)
        // Messages or Text Messages, the icon that started it; the alias's
        // label is the window's title.
        state = state.reduce(opened(intent))
        showTitle(state)
        // The feed outlives an activity: it shows this one's view from the start.
        graph.feed.show(state.filter, state.search, state.account, state.searchIn)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { graph.feed.state.collect(::fed) }
        loadImages()
        render(state)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        graph.reader.demo = intent.getIntExtra(EXTRA_ROWS, 0)
        update(opened(intent))
        graph.feed.reload()
    }

    /**
     * Messages, Text Messages, or a mail account's icon (its shortcut, AccountShortcuts).
     * The intent is the launcher's or the Hub's: an unknown account only shows nothing.
     */
    private fun opened(intent: Intent) =
        InboxEvent.Opened(entryView(intent.component?.className, intent.getStringExtra(EXTRA_VIEW)), intent.getStringExtra(EXTRA_MAIL_ACCOUNT))

    // Watched while shown: every write of the Hub's store notifies its counters' URI (HubContract).
    override fun onStart() {
        super.onStart()
        graph.ioThread.post {
            // The showcase asks no account.
            showcase = showcaseOn(this)
            if (showcase) return@post
            // The accounts, and their icons on the home kept in step with them (AccountShortcuts).
            val accounts = graph.mail.accounts() ?: return@post
            AccountShortcuts.publish(this, accounts.list)
            runOnUiThread {
                mail = accounts.list
                showTitle(state)
            }
        }
        graph.ioThread.post {
            // The video's story (bold7_showcase_event): its text arrives in the showcase's inbox.
            contentResolver.registerContentObserver(Settings.Global.getUriFor(SHOWCASE_EVENT_SETTING), false, observer)
            try {
                contentResolver.registerContentObserver(Uri.parse(HubContract.COUNTS_URI), false, observer)
            } catch (e: SecurityException) {
                Log.w(TAG, "Hub changes not observed: ${e.javaClass.name}") // apps/hub not installed
            }
        }
        graph.feed.reload()
    }

    override fun onStop() {
        graph.ioThread.post { contentResolver.unregisterContentObserver(observer) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        graph.feed.view(true)
    }

    override fun onPause() {
        letters.clear()
        graph.feed.view(false)
        super.onPause()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        takeFocus()
    }

    /** Android's focus: on the search field's editor while the list's focus is on it, else on the page; the trackpad captured by it. */
    private fun takeFocus() {
        val view = if (onField()) search else root
        view.requestFocus()
        view.requestPointerCapture()
    }

    private fun onField() = state.screen == Screen.LIST && state.focus == FIELD && state.search != null

    /** The status icons, the scroll arrows and the short menu's images, decoded off the main thread. */
    private fun loadImages() = scope.launch {
        val sm = mapOf(
            Act.OPEN to "msg_sm_open", Act.MARK_READ to "msg_sm_mark_read", Act.MARK_UNREAD to "msg_sm_mark_unread",
            Act.MARK_PRIOR_READ to "msg_sm_mark_prior_opened", Act.DELETE to "msg_sm_delete", Act.DELETE_PRIOR to "msg_sm_delete",
            Act.COMPOSE to "msg_sm_compose", Act.COMPOSE_EMAIL to "msg_sm_compose", Act.SEARCH to "msg_sm_search", Act.VIEW_FOLDER to "msg_sm_select_folder",
            Act.OPTIONS to "msg_sm_options_generic",
        )
        val decoded = withContext(graph.io) {
            strips.message = decode("msg_message_icons")
            strips.phone = decode("msg_phone_icons")
            arrows.up = decode("msg_scroll_up")
            arrows.down = decode("msg_scroll_down")
            sm.mapValues { decode(it.value) }
        }
        decoded.forEach { (act, bitmap) -> glyphs[act.ordinal] = bitmap }
        list.invalidateViews()
        arrows.invalidate()
        menuView.invalidate()
    }

    private fun decode(name: String): Bitmap = themeBitmap(applicationContext, name)

    // State

    /** The feed's lines, kept only when worked out for the view shown. */
    private fun fed(f: FeedState) {
        if (!f.loaded || f.filter != state.filter || f.search != state.search || f.account != state.account || f.searchIn != state.searchIn) return
        update(InboxEvent.Shown(f.lines))
    }

    private fun update(event: InboxEvent) {
        val old = state
        state = state.reduce(event)
        render(old)
    }

    private fun set(next: InboxState) {
        val old = state
        state = next
        render(old)
    }

    private fun render(old: InboxState) {
        val s = state
        if (s.filter != old.filter || s.search != old.search || s.account != old.account || s.searchIn != old.searchIn) graph.feed.show(s.filter, s.search, s.account, s.searchIn)
        if (s.filter != old.filter || s.account != old.account) showTitle(s)
        listPage.visibility = if (s.screen == Screen.LIST) View.VISIBLE else View.GONE
        folderPage.visibility = if (s.screen == Screen.FOLDERS) View.VISIBLE else View.GONE
        search.visibility = if (s.search == null) View.GONE else View.VISIBLE
        if (s.search != null && search.text.toString() != s.search) {
            binding = true
            search.setText(s.search)
            search.setSelection(s.search.length)
            binding = false
        }
        val wasOnField = old.screen == Screen.LIST && old.focus == FIELD && old.search != null
        if (onField() != wasOnField) takeFocus()
        val none = s.loaded && s.lines.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        // sim-43: the empty inbox is one date field's band, blue, "* No Messages *" (C).
        if (none) empty.show(getString(R.string.no_messages), lit = true)
        when {
            s.lines !== old.lines -> lines.notifyDataSetChanged()
            s.focus != old.focus -> rebindShown()
        }
        // Only when the focus goes to another line: not on a reload, nor when a message arriving
        // above moves its line down; a list scrolled by touch stays where the finger left it.
        if (s.focus >= 0 && (s.focusedLine?.id != old.focusedLine?.id || s.screen != old.screen)) keepInSight(s.focus)
        if (s.screen == Screen.FOLDERS && (s.folder != old.folder || s.screen != old.screen)) {
            folderAdapter.notifyDataSetChanged()
            folders.setSelection(s.folder)
        }
    }

    private fun rebindShown() {
        for (i in 0 until list.childCount) bind(list.getChildAt(i), list.firstVisiblePosition + i)
    }

    /** The focused line wholly on screen: at the top when it came from above, at the bottom from below. */
    private fun keepInSight(position: Int) {
        val line = state.lines.getOrNull(position) ?: return
        val h = if (line is DayLine) ListHeaderView.HEIGHT else MessageRowView.HEIGHT
        val child = list.getChildAt(position - list.firstVisiblePosition)
        when {
            child == null -> list.setSelectionFromTop(position, if (position > list.firstVisiblePosition) list.height - h else 0)
            child.top < 0 -> list.setSelectionFromTop(position, 0)
            child.bottom > list.height -> list.setSelectionFromTop(position, list.height - h)
        }
    }

    private val page get() = (list.height / ListDimens.ROW).coerceAtLeast(1)

    // Menus

    private fun label(act: Act) = getString(
        when (act) {
            Act.OPEN -> R.string.act_open
            Act.MARK_READ -> R.string.act_mark_read
            Act.MARK_UNREAD -> R.string.act_mark_unread
            Act.MARK_PRIOR_READ -> R.string.act_mark_prior_read
            Act.DELETE -> R.string.act_delete
            Act.DELETE_PRIOR -> R.string.act_delete_prior
            Act.COMPOSE -> R.string.act_compose
            Act.COMPOSE_OTHER -> R.string.act_compose_other
            Act.COMPOSE_TEXT -> R.string.act_text_message
            Act.COMPOSE_EMAIL -> R.string.act_compose_email
            Act.OPTIONS -> R.string.act_options
            Act.HELP -> R.string.act_help
            Act.CLOSE -> R.string.mail_close
            Act.SEARCH -> R.string.act_search
            Act.SEARCH_BY -> R.string.act_search_by
            Act.SEARCH_SENDER -> R.string.act_by_sender
            Act.SEARCH_RECIPIENT -> R.string.act_by_recipient
            Act.SEARCH_SUBJECT -> R.string.act_by_subject
            Act.DISPLAY_STYLE -> R.string.act_display_style
            Act.STYLE_GROUP -> R.string.act_style_group
            Act.STYLE_SINGLE -> R.string.act_style_single
            Act.VIEW_SAVED -> R.string.act_view_saved
            Act.SAVE -> R.string.act_save
            Act.NOTIFIERS -> R.string.act_notifiers
            Act.SWITCH -> R.string.act_switch_application
            else -> R.string.act_view_folder
        },
    )

    /**
     * The screen's title, which the status bar shows in the middle of its first line (SystemUI reads
     * the task's label): an account's view its address (gsm 029, C), a folder its name (sim-53, C),
     * and the unified box none, so that the bar names the network (sim-43, C).
     */
    private fun viewTitle(s: InboxState): String? =
        s.account?.takeIf { s.filter == s.base }?.let { a -> mail.firstOrNull { it.id == a }?.address }
            ?: if (s.filter == Filter.ALL) null else getString(folderName(s.filter))

    private fun showTitle(s: InboxState) {
        val shown = viewTitle(s)
        title = shown ?: getString(folderName(s.filter))
        setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(shown.orEmpty()).build())
    }

    private fun folderName(f: Filter) = when (f) {
        Filter.ALL -> R.string.folder_all
        Filter.INCOMING -> R.string.folder_incoming
        Filter.OUTGOING -> R.string.folder_outgoing
        Filter.TEXT -> R.string.folder_text
        Filter.CALLS -> R.string.folder_calls
        Filter.UNREAD -> R.string.folder_unread
        Filter.NOTIFICATIONS -> R.string.folder_notifications
        Filter.SAVED -> R.string.folder_saved
    }

    /**
     * Binds [m]. A short menu expanded (its Full Menu cell, or the Menu key on it) shows the full
     * menu of OS 7 over the same line, not the short menu's own entries.
     */
    private fun show(m: Menu<Act>?) {
        val shown = if (m != null && m.line != null && m.expanded) menuOf(state.focusedLine, state.filter) else m
        menu = shown
        menuView.bind(shown)
    }

    /** The short menu over the focused line; none on the search field or View Folder. */
    private fun holdLine(): Boolean {
        if (state.screen != Screen.LIST || state.focus == FIELD) return false
        show(Menu(shortMenu(state.focusedLine, state.filter), line = state.focus.coerceAtLeast(0), highlighted = 0))
        return true
    }

    private fun chosen(index: Int) {
        val act = menu?.entries?.getOrNull(index)?.action ?: return
        // A submenu replaces the menu, which MenuView keeps drawn behind it (isSubmenu).
        when (act) {
            Act.SEARCH_BY -> return show(searchByMenu(state.focusedLine))
            Act.COMPOSE_OTHER -> return show(composeOtherMenu())
            Act.DISPLAY_STYLE -> return show(displayStyleMenu())
            else -> Unit
        }
        show(null)
        perform(act)
    }

    private fun onMenuKey(m: Menu<Act>, key: MenuKey) {
        when (val r = m.key(key)) {
            is MenuResult.Show -> show(r.menu)
            is MenuResult.Choose -> chosen(r.index)
            MenuResult.Close -> show(null)
        }
    }

    // Actions

    private fun perform(act: Act) {
        val line = state.focusedLine
        act.filter?.let {
            update(InboxEvent.Filtered(it))
            return
        }
        if (showcase && act in WRITES) return
        when (act) {
            Act.OPEN -> (line as? ItemLine)?.let(::open)
            Act.MARK_READ, Act.MARK_UNREAD -> (line as? ItemLine)?.let { write(Request(act, it.item)) }
            Act.TOGGLE_READ -> (line as? ItemLine)?.let { write(Request(if (it.unread) Act.MARK_READ else Act.MARK_UNREAD, it.item)) }
            Act.DELETE -> (line as? ItemLine)?.let {
                ask(graph.store.options.confirmDelete, R.string.confirm_delete, R.string.button_delete, focused = 0) {
                    when {
                        // The saved messages' own Delete takes the message out of them only (guide p. 93, B).
                        state.filter == Filter.SAVED -> unsave(it.item.savedKey())
                        // A draft is this app's own file, not the Hub's.
                        it.item.accountId == DRAFTS_ACCOUNT -> if (!locked()) scope.launch(graph.io) { graph.drafts.delete(it.item.sourceKey) }
                        else -> write(Request(Act.DELETE, it.item))
                    }
                }
            }
            Act.MARK_PRIOR_READ, Act.DELETE_PRIOR -> (line as? DayLine)?.let {
                // What arrives while the dialog shows is not written.
                val asOf = System.currentTimeMillis()
                val delete = act == Act.DELETE_PRIOR
                ask(
                    graph.store.options.run { if (delete) confirmDelete else confirmMarkPriorRead },
                    if (delete) R.string.confirm_delete_prior else R.string.confirm_mark_prior_read,
                    if (delete) R.string.button_delete else R.string.button_yes,
                    // A whole span deleted: Cancel has the focus (D).
                    focused = if (delete) 1 else 0,
                ) { write(Request(act, day = it.date, asOf = asOf)) }
            }
            Act.SWITCH -> SwitchApplication.start(this)
            Act.COMPOSE, Act.COMPOSE_TEXT -> compose()
            // From an account's view, the mail leaves from that account.
            Act.COMPOSE_EMAIL -> if (!locked()) startActivity(Intent(this, ComposeMailActivity::class.java).putExtra(EXTRA_MAIL_ACCOUNT, state.account))
            Act.OPTIONS -> if (!locked()) startActivity(Intent(this, MessagesOptionsActivity::class.java))
            Act.SEARCH_SENDER, Act.SEARCH_RECIPIENT, Act.SEARCH_SUBJECT -> searchBy(line)
                .firstOrNull { it.first == act }?.let { update(InboxEvent.SearchedBy(searchIn(act), it.second)) }
            Act.STYLE_GROUP, Act.STYLE_SINGLE -> setOptions(graph.store.options.copy(group = act == Act.STYLE_GROUP))
            Act.VIEW_SAVED -> update(InboxEvent.Filtered(Filter.SAVED))
            Act.SAVE -> (line as? ItemLine)?.let { save(it.item.savedKey()) }
            Act.NOTIFIERS -> if (!locked()) notifiers()
            Act.SEARCH -> update(InboxEvent.SearchOpened)
            // Help has no source for its text (unsourced): it only closes the menu, as the Calculator's does.
            Act.HELP -> dev.bold7.ui.Help.open(this, "messages_index")
            Act.CLOSE -> finish()
            Act.VIEW_FOLDER -> update(InboxEvent.FoldersOpened)
            else -> navigate(act, state.lines, state.focus, page)?.let { update(InboxEvent.Moved(it)) }
        }
    }

    /**
     * Writes through the Hub's provider, and for texts through Google Messages' API first (Feed,
     * apiCalls): read and deleted there too; Mark Unread stays the Hub's. Refused while
     * locked, by the provider too.
     */
    private fun write(request: Request) {
        if (locked()) return
        graph.feed.act(request)
    }

    private fun searchIn(act: Act) = when (act) {
        Act.SEARCH_SENDER -> SearchIn.SENDER
        Act.SEARCH_RECIPIENT -> SearchIn.RECIPIENT
        else -> SearchIn.SUBJECT
    }

    /** Display Style's choice, kept; the list is worked out again from the last read. */
    private fun setOptions(o: MessagesOptions) {
        graph.ioThread.post { graph.store.update(o); graph.feed.reload() }
    }

    private fun save(key: String) {
        graph.ioThread.post { graph.store.save(key); graph.feed.reload() }
    }

    private fun unsave(key: String) {
        graph.ioThread.post { graph.store.unsave(key); graph.feed.reload() }
    }

    /** Message Notifiers: the profiles' Messages and Email sounds, in Options of the launcher. */
    private fun notifiers() {
        try {
            startActivity(Intent().setClassName(LAUNCHER, "$LAUNCHER.options.OptionsActivity").putExtra("dev.bold7.options.SCREEN", "NOTIFIER").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            status(this, getString(R.string.no_options))
        }
    }

    /** [then] at once when the option [asks] for no question, else after the dialog (Confirm Delete, Confirm Mark Prior Read). */
    private fun ask(asks: Boolean, text: Int, positive: Int, focused: Int, then: () -> Unit) =
        if (asks) confirm(text, positive, focused, then) else then()

    private fun confirm(text: Int, positive: Int, focused: Int, then: () -> Unit) {
        dialog?.dismiss()
        dialog = Bold7Dialog(
            this,
            getString(text),
            listOf(getString(positive), getString(R.string.button_cancel)),
            DialogIcon.QUESTION,
            focused = focused,
            onButton = { if (it == 0) then() },
        ).also { it.show() }
    }

    /** Nothing interactive while the phone is locked (CONTRIBUTING.md). */
    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /**
     * Compose Text Message: the thread's To band (sim-49) while Google Messages' API is open;
     * shut, the status message says why and Google Messages' own composer opens, as
     * before.
     */
    private fun compose() {
        if (locked()) return
        throughApi(
            open = { startActivity(ConversationActivity.intent(this, null, "")) },
            shut = {
                val app = withContext(graph.io) { Telephony.Sms.getDefaultSmsPackage(this@MessagesActivity) }
                start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).setPackage(app).addFlags(GOOGLE_MESSAGES_TASK))
            },
        )
    }

    /**
     * [open] when Google Messages' API answers with its whole contract (GetServiceCapabilities,
     * asked at each opening: Google Messages may have changed in between); otherwise OS 7's status
     * message says why and [shut] runs once it has been read (if the API is unavailable, the user is told). A reason that may be Google Messages starting cold is asked again
     * (awaitDoor): the list stays usable meanwhile, a second Open is ignored.
     */
    private fun throughApi(open: () -> Unit, shut: suspend () -> Unit) {
        if (asking?.isActive == true) return
        asking = scope.launch { throughApiNow(open, shut) }
    }

    private suspend fun throughApiNow(open: () -> Unit, shut: suspend () -> Unit) {
        // Simplification: 3 tries 1 s apart, each up to GrpcMessagingApi's 15 s deadline when Google
        // Messages hangs; a shorter deadline for state() alone if that is ever seen.
        when (val api = withContext(graph.io) { awaitDoor(tries = 3, pauseMs = 1_000) { graph.api.state() } }) {
            ApiState.Open -> open()
            is ApiState.Shut -> {
                Log.w(TAG, "Google Messages' API shut: ${api.why}")
                status(this@MessagesActivity, getString(R.string.api_shut, getString(whyText(api.why))))
                delay(Durations.STATUS_MS)
                shut()
            }
        }
    }

    private fun whyText(why: Why) = when (why) {
        Why.NO_PERMISSION -> R.string.why_permission
        Why.CLOSED -> R.string.why_closed
        Why.NO_CONSENT -> R.string.why_consent
        Why.REFUSED -> R.string.why_refused
        Why.CONTRACT -> R.string.why_contract
        Why.FAILED -> R.string.why_failed
    }

    /** Open: a text in the thread of OS 7, any other item in its own app. */
    private fun open(line: ItemLine) {
        if (locked() || line.item.sourceKey.startsWith("demo:")) return
        if (line.item.sourceKey.startsWith(SHOWCASE_KEY)) return openShowcase(line)
        when (val o = opening(line.item)) {
            is Opening.Notification -> scope.launch {
                val intent = withContext(graph.io) { graph.reader.open(o.key) }
                if (intent != null && send(intent)) return@launch
                val app = o.app?.let { withContext(graph.io) { packageManager.getLaunchIntentForPackage(it) } }
                if (app == null || !start(app)) unable(line)
            }
            // The thread of OS 7; the door shut, Google Messages on sms:a;b;c, every
            // member of a group, as AOSP's Mms app writes its recipients.
            is Opening.Conversation -> {
                val thread = line.item.conversationId?.toLongOrNull()
                throughApi(
                    open = { if (thread != null) startActivity(ConversationActivity.intent(this, thread, line.sender)) else unable(line) },
                    shut = {
                        val sms = if (o.addresses.isEmpty()) {
                            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
                        } else {
                            Intent(Intent.ACTION_VIEW, Uri.parse("sms:" + o.addresses.joinToString(";") { Uri.encode(it) }))
                        }
                        if (!start(sms.addFlags(GOOGLE_MESSAGES_TASK))) unable(line)
                    },
                )
            }
            // "Call …?" of OS 7 over Messages (sim-104), asked by the Phone of Bold7 (CALL_PROMPT,
            // signature); without it, the Phone that holds DIALER on its log.
            is Opening.Call -> if (!start(callPrompt(o.number)) && !start(Intent(Intent.ACTION_DIAL))) unable(line)
            Opening.CallLog -> if (!start(Intent(Intent.ACTION_DIAL))) unable(line)
            // An invitation: Bold7's Calendar, where Accept, Tentative and Decline are;
            // without it, whatever calendar Android has.
            is Opening.Event -> Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, o.id)).let { view ->
                if (!start(Intent(view).setPackage(CALENDAR_PACKAGE)) && !start(view)) unable(line)
            }
            // read in Messages; a draft written again.
            is Opening.Mail -> startActivity(MailActivity.intent(this, o.key))
            is Opening.Draft -> startActivity(Intent(this, ComposeMailActivity::class.java).putExtra(ComposeMailActivity.EXTRA_DRAFT, o.id))
            Opening.None -> unable(line)
        }
    }

    /** A showcase item: its own made-up thread or email; a missed call opens nothing (ShowcaseInbox). */
    private fun openShowcase(line: ItemLine) {
        val item = line.item
        when (item.kind) {
            ItemKind.SMS, ItemKind.MMS -> item.conversationId?.toLongOrNull()?.let { startActivity(ConversationActivity.intent(this, it, line.sender)) }
            ItemKind.EMAIL -> startActivity(MailActivity.intent(this, ItemKey(item.accountId, item.sourceKey)))
            else -> Unit
        }
    }

    /** An item that opens nothing says so in OS 7's status message (sim-42, C; wording D). */
    private fun unable(line: ItemLine) {
        Log.i(TAG, "open ${line.item.kind}: unavailable")
        status(this, getString(R.string.unable_to_open, line.sender))
    }

    private fun start(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        // CALL_PROMPT not granted: the Phone of Bold7 is missing or was installed oddly.
        Log.w(TAG, "start refused for $intent", e)
        false
    }

    /** The Phone of Bold7's "Call [number]?" (apps/phone PromptActivity). */
    private fun callPrompt(number: String) =
        Intent("dev.bold7.phone.CALL_PROMPT", Uri.fromParts("tel", number, null)).setPackage("dev.bold7.phone")

    /** Sends a notification's contentIntent as the shade does, lending it this foreground app's right to start an activity. */
    private fun send(intent: PendingIntent): Boolean = try {
        val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        intent.send(this, 0, null, null, null, null, options.toBundle())
        true
    } catch (e: PendingIntent.CanceledException) {
        false
    }

    // Keys and the trackpad

    /** A click: a message opens (guide p. 94: "Open a message: press Enter"), a date field shows its short menu (D). */
    private fun clicked() {
        when (state.screen) {
            Screen.FOLDERS -> update(InboxEvent.Filtered(FOLDER_VIEWS[state.folder]))
            Screen.LIST -> when (state.focusedLine) {
                is ItemLine -> perform(Act.OPEN)
                is DayLine -> holdLine()
                null -> if (state.focus == FIELD) update(InboxEvent.Step(down = true))
            }
        }
    }

    private fun move(dir: Dir) {
        menu?.let {
            onMenuKey(it, when (dir) {
                Dir.UP -> MenuKey.UP
                Dir.DOWN -> MenuKey.DOWN
                Dir.LEFT -> MenuKey.LEFT
                Dir.RIGHT -> MenuKey.RIGHT
            })
            return
        }
        if (dir == Dir.LEFT || dir == Dir.RIGHT) return
        val down = dir == Dir.DOWN
        update(if (state.screen == Screen.FOLDERS) InboxEvent.FolderStep(down) else InboxEvent.Step(down))
    }

    private val hold = Runnable { if (clicking && menu == null) held = holdLine() }
    private var clicking = false

    /** The trackpad as a relative pointer: its steps move the focus, its button clicks, held it opens the short menu. */
    private fun onPointer(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> if (!clicking) {
                clicking = true
                root.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> if (clicking) {
                clicking = false
                root.removeCallbacks(hold)
                val m = menu
                when {
                    held -> held = false
                    m != null -> onMenuKey(m, MenuKey.CLICK)
                    else -> clicked()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                clicking = false
                root.removeCallbacks(hold)
            }
            MotionEvent.ACTION_MOVE -> {
                // Each sample is a move from the one before.
                var dx = e.x
                var dy = e.y
                for (i in 0 until e.historySize) {
                    dx += e.getHistoricalX(i)
                    dy += e.getHistoricalY(i)
                }
                val (next, steps) = travel.moved(dx, dy)
                travel = next
                for (d in steps) move(d)
            }
        }
        return true
    }

    // A plain letter of the list waits for its up: held, it calls its speed dial (guide 7.1 p. 65).
    private val letters = LetterHold()

    private fun letterOut(out: LetterHold.Out) = when (out) {
        is LetterHold.Out.Tap -> shortcut(out.letter, alt = false, mail = mail.isNotEmpty())?.let(::perform) ?: Unit
        is LetterHold.Out.Hold -> SpeedDialKey.call(this, out.letter)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (menu != null || !e.isLetterOfTheList()) letters.flush()?.let(::letterOut)
        if (e.action == KeyEvent.ACTION_UP) letters.up(code)?.let { letterOut(it); return true }
        // What types goes to the search field's editor (the input method has seen it first).
        if (menu == null && onField() && searchTakes(fieldKey(code))) return super.dispatchKeyEvent(e)
        // The click acts on its release, unless held into a short menu (ViewConfiguration's long press).
        if (e.action == KeyEvent.ACTION_UP) {
            if (!click) return super.dispatchKeyEvent(e)
            when {
                held -> held = false
                menu == null -> clicked()
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val m = menu
        if (m != null) {
            if (click && e.repeatCount > 0) return true
            onMenuKey(m, when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> if (click) MenuKey.CLICK else return true
            })
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> move(Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(Dir.DOWN)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> set(state.back() ?: return super.dispatchKeyEvent(e))
            KeyEvent.KEYCODE_MENU -> if (state.screen == Screen.LIST) show(menuOf(state.focusedLine, state.filter))
            else -> when {
                // Held: its first repeat comes after the long press timeout.
                click -> if (e.isLongPress || e.repeatCount == 1) held = holdLine()
                state.screen != Screen.LIST -> return super.dispatchKeyEvent(e)
                state.focus == FIELD -> return super.dispatchKeyEvent(e)
                code == KeyEvent.KEYCODE_SPACE -> perform(if (e.isShiftPressed) Act.PAGE_UP else Act.PAGE_DOWN)
                e.isLetterOfTheList() -> letters.down(code, e.displayLabel, e.repeatCount).forEach(::letterOut)
                else -> perform(shortcut(e.displayLabel, e.isAltPressed, mail = mail.isNotEmpty()) ?: return super.dispatchKeyEvent(e))
            }
        }
        return true
    }

    /** A letter of the list with no Alt, which is a shortcut or a speed dial key. */
    private fun KeyEvent.isLetterOfTheList() = state.screen == Screen.LIST && state.focus != FIELD && !isAltPressed && displayLabel.lowercaseChar() in 'a'..'z'

    // Adapters

    private fun bind(view: View, position: Int) {
        val lit = position == state.focus
        when (val line = state.lines.getOrNull(position)) {
            is DayLine -> (view as? ListHeaderView)?.show(line.title, lit)
            is ItemLine -> (view as? MessageRowView)?.bind(line, lit)
            null -> Unit
        }
    }

    private inner class LinesAdapter : BaseAdapter() {
        override fun getCount() = state.lines.size
        override fun getItem(position: Int) = state.lines[position]
        override fun getItemId(position: Int) = state.lines[position].id
        override fun hasStableIds() = true
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (state.lines[position] is DayLine) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: if (state.lines[position] is DayLine) ListHeaderView(this@MessagesActivity) else MessageRowView(this@MessagesActivity, strips)
            bind(view, position)
            return view
        }
    }

    private inner class FoldersAdapter : BaseAdapter() {
        override fun getCount() = FOLDER_VIEWS.size
        override fun getItem(position: Int) = FOLDER_VIEWS[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? ListItemView) ?: ListItemView(this@MessagesActivity)).apply {
                bind(getString(folderName(FOLDER_VIEWS[position])), "", lit = position == state.folder)
            }
    }

    private companion object {
        const val EXTRA_ROWS = "rows"

        /** What the showcase does not do: every write, of the Hub or of Messages' own options, and every new message. */
        val WRITES = setOf(
            Act.MARK_READ, Act.MARK_UNREAD, Act.TOGGLE_READ, Act.MARK_PRIOR_READ, Act.DELETE, Act.DELETE_PRIOR, Act.SAVE,
            Act.COMPOSE, Act.COMPOSE_TEXT, Act.COMPOSE_EMAIL, Act.STYLE_GROUP, Act.STYLE_SINGLE, Act.OPTIONS, Act.NOTIFIERS,
        )
    }
}
