package dev.bold7.calendar

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.CalendarContract
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.setContentUnderBar
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.Spin
import dev.bold7.ui.SpinDialog
import dev.bold7.ui.status
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Calendar of BBOS 7.1 (docs/BBOS7-VISUEL.md §29): the Day, Week, Month and Agenda views of
 * Android's calendar provider, the full menu, the guide's keys (p. 58). It decides nothing: the
 * focus, the click and the menu are [CalendarScreen]'s; the provider is read on the io thread
 * ([AndroidAgenda]) again whenever it changes (a ContentObserver, no polling).
 *
 * It opens, as the simulator, on the Day view of today, the hour of the moment under the focus
 * (sim-220). An appointment opens in the form ([EventActivity]); so does an event of the Hub's
 * invitations (VIEW of content://com.android.calendar/events/<id>, from Messages).
 */
class CalendarActivity : Activity() {
    private val graph get() = application.graph
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val options = DisplayOptions()
    private lateinit var view: CalendarView
    private lateinit var menuView: MenuView<Act>
    private var menu: Menu<Act>? = null
    private var parent: Menu<Act>? = null

    /** Quick Entry's subject while its box is open on the focused hour (the Day view). */
    private var quick: String? = null
    private var screen = LocalDateTime.now().let { CalendarScreen(CalView.DAY, it.toLocalDate(), it.hour) }
    private var occurrences = emptyList<Occurrence>()
    private var agenda = emptyList<AgendaRow>()
    private var loaded = false
    private var generation = 0

    // On the main thread, which owns the screen's state; the reading itself goes to io.
    private val changes = object : ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = CalendarView(this) { tapped(it) }
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = { i -> menu?.let { chosen(it.entries[i].action) } }, onExpand = { menu?.let { showMenu(it.expand()) } }, onClose = { showMenu(null) })
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        // Under the one-line bar, as the 9900 draws it (the view's first row is at y 35).
        setContentUnderBar(FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(match, match))
            addView(menuView, FrameLayout.LayoutParams(match, match))
        })
        view.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP) view.tap(e.x, e.y); true }
        graph.io.post {
            val images = CalendarView.Images(
                BitmapFactory.decodeResource(resources, R.drawable.cal_bell),
                BitmapFactory.decodeResource(resources, R.drawable.cal_arrow_left),
                BitmapFactory.decodeResource(resources, R.drawable.cal_arrow_right),
                BitmapFactory.decodeResource(resources, R.drawable.cal_allday),
            )
            mainExecutor.execute { view.images = images }
        }
        opened(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opened(intent)
    }

    /** VIEW of an event (the Hub's invitations in Messages): its form, over the calendar. */
    private fun opened(intent: Intent) {
        val data = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW || data.authority != CalendarContract.AUTHORITY) return
        val id = runCatching { ContentUris.parseId(data) }.getOrNull()?.takeIf { it > 0 } ?: return
        startActivity(EventActivity.open(this, id, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0), intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0)))
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, changes)
        load()
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(changes)
        super.onStop()
    }

    /** The occurrences of the view's range, read on io; a newer request wins over an older one. */
    private fun load() {
        val s = screen
        val g = ++generation
        graph.io.post {
            val (from, to) = range(s.view, s.day, zone, options)
            val read = graph.agenda.occurrences(from, to)
            val rows = if (s.view == CalView.AGENDA) agendaRows(s.day, AGENDA_DAYS, read, zone) else emptyList()
            mainExecutor.execute {
                if (g != generation) return@execute
                occurrences = read
                agenda = rows
                loaded = true
                render()
            }
        }
    }

    private fun render() = view.bind(Shown(screen, occurrences, agenda, LocalDateTime.now(), zone, options, quick))

    /** A new state: drawn now, read again when its range changed. */
    private fun go(next: CalendarScreen) {
        val old = screen
        screen = next
        val moved = old.view != next.view || range(old.view, old.day, zone, options) != range(next.view, next.day, zone, options)
        if (moved) {
            if (next.view == CalView.AGENDA) agenda = emptyList()
            load()
        }
        render()
    }

    private fun inHour() = occurrences.filter { it.covers(screen.day, screen.hour, zone) }

    private fun focused() = screen.focused(agenda, inHour())

    // A tap focuses; a tap on the focus clicks (D).
    private fun tapped(next: CalendarScreen): Unit {
        if (quick != null) closeQuick()
        if (next == screen) click() else go(next)
    }

    private fun click() {
        if (!loaded) return
        when (val c = screen.click(agenda, inHour())) {
            is Click.Open -> open(c.occurrence)
            is Click.New -> startActivity(EventActivity.new(this, c.day, c.hour))
            is Click.ShowDay -> go(screen.copy(view = CalView.DAY, day = c.day))
        }
    }

    private fun open(o: Occurrence) = startActivity(EventActivity.open(this, o.eventId, o.begin, o.end))

    // Menu

    private fun label(a: Act) = getString(
        when (a) {
            Act.NEW -> R.string.act_new
            Act.NEW_ALARM -> R.string.act_new_alarm
            Act.NEW_TASK -> R.string.act_new_task
            Act.OPEN -> R.string.act_open
            Act.DELETE -> R.string.act_delete
            Act.ACCEPT -> R.string.act_accept
            Act.TENTATIVE -> R.string.act_tentative
            Act.DECLINE -> R.string.act_decline
            Act.TODAY -> R.string.act_today
            Act.GO_TO_DATE -> R.string.act_go_to_date
            Act.VIEW -> R.string.act_view
            Act.DAY -> R.string.act_day
            Act.WEEK -> R.string.act_week
            Act.MONTH -> R.string.act_month
            Act.AGENDA -> R.string.act_agenda
            Act.CLOSE -> R.string.act_close
            Act.SWITCH -> R.string.act_switch_application
        },
    )

    private fun showMenu(m: Menu<Act>?) {
        if (m == null) parent = null
        menu = m
        menuView.bind(m)
    }

    private fun chosen(a: Act) {
        if (a == Act.VIEW) {
            // The submenu opens at the menu's right, the menu staying behind it (isSubmenu); Back returns to it.
            val main = menu ?: return
            val back = main.copy(highlighted = main.entries.indexOfFirst { it.action == Act.VIEW })
            showMenu(back)
            showMenu(Menu(viewMenu(screen.view), highlighted = 0))
            parent = back
            return
        }
        showMenu(null)
        val f = focused()
        when (a) {
            Act.NEW -> startActivity(EventActivity.new(this, screen.day, screen.hour.takeIf { screen.view == CalView.DAY || screen.view == CalView.WEEK }))
            // Explicit intents to the sibling apps' exported launcher activities (sim-223): Clock's Set
            // Alarm, a blank task. A sibling that is not installed is said, never a crash.
            Act.NEW_ALARM -> sibling("dev.bold7.clock", "dev.bold7.clock.ClockActivity", "dev.bold7.clock.SET_ALARM")
            Act.NEW_TASK -> sibling("dev.bold7.tasks", "dev.bold7.tasks.TaskListActivity", "dev.bold7.tasks.NEW")
            Act.OPEN -> f?.let(::open)
            Act.DELETE -> f?.let(::delete)
            Act.ACCEPT -> f?.let { answer(it, ATTENDEE_ACCEPTED) }
            Act.TENTATIVE -> f?.let { answer(it, ATTENDEE_TENTATIVE) }
            Act.DECLINE -> f?.let { answer(it, ATTENDEE_DECLINED) }
            Act.TODAY -> today()
            Act.GO_TO_DATE -> SpinDialog(this, Spin.date(screen.day)) { go(screen.goTo(it)) }.show()
            Act.VIEW -> Unit // opened above, with the menu kept behind it
            Act.DAY -> go(screen.show(CalView.DAY))
            Act.WEEK -> go(screen.show(CalView.WEEK))
            Act.MONTH -> go(screen.show(CalView.MONTH))
            Act.AGENDA -> go(screen.show(CalView.AGENDA))
            Act.CLOSE -> finish()
            Act.SWITCH -> SwitchApplication.start(this)
        }
    }

    private fun sibling(pkg: String, cls: String, extra: String) {
        try {
            startActivity(Intent().setClassName(pkg, cls).putExtra(extra, true))
        } catch (_: ActivityNotFoundException) {
            status(this, getString(R.string.not_available))
        } catch (_: SecurityException) {
            status(this, getString(R.string.not_available))
        }
    }

    private fun today() {
        val now = LocalDateTime.now()
        go(screen.goTo(now.toLocalDate()).copy(hour = now.hour))
    }

    /** "Delete appointment?" Delete, Cancel under the focus (sim-246); a recurring one asks which (D). */
    private fun delete(o: Occurrence) {
        val buttons = if (o.recurring) listOf(R.string.delete_all, R.string.delete_one, R.string.cancel) else listOf(R.string.act_delete, R.string.cancel)
        Bold7Dialog(
            this, getString(if (o.recurring) R.string.delete_recurring else R.string.delete_question), buttons.map(::getString), DialogIcon.QUESTION, buttons.size - 1,
            onButton = { i ->
                if (i == buttons.size - 1) return@Bold7Dialog
                graph.io.post {
                    val ok = if (o.recurring && i == 1) graph.agenda.deleteOccurrence(o.eventId, o.begin) else graph.agenda.delete(o.eventId)
                    if (!ok) mainExecutor.execute { status(this, getString(R.string.refused)) }
                }
            },
        ).show()
    }

    private fun answer(o: Occurrence, status: Int) = graph.io.post {
        val ok = graph.agenda.answer(o.eventId, status)
        mainExecutor.execute { status(this, getString(if (ok) R.string.answer_sent else R.string.refused)) }
    }

    // Quick Entry

    private fun closeQuick() {
        quick = null
        render()
    }

    /** A key while the box is open: text goes in, Backspace out, Enter saves the appointment (one hour long, D), Back drops it. */
    private fun quickKey(e: KeyEvent): Boolean {
        val subject = quick ?: return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> closeQuick()
            KeyEvent.KEYCODE_DEL -> {
                quick = quickBackspace(subject)
                render()
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> if (e.repeatCount == 0) saveQuick(subject)
            else -> e.unicodeChar.takeIf { it >= ' '.code }?.let {
                quick = subject + it.toChar()
                render()
            }
        }
        return true
    }

    private fun saveQuick(subject: String) {
        val day = screen.day
        val hour = screen.hour
        closeQuick()
        graph.io.post {
            val calendar = graph.agenda.defaultCalendar()
            val id = calendar?.let { graph.agenda.save(Draft.new(day, hour, LocalDateTime.now(), it, zone).copy(subject = subject)) }
            if (id == null) mainExecutor.execute { status(this, getString(R.string.refused)) }
        }
    }

    // Keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val code = e.keyCode
        val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        val m = menu
        if (m != null) {
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
                is MenuResult.Show -> showMenu(r.menu)
                is MenuResult.Choose -> chosen(m.entries[r.index].action)
                // Back over a submenu: the menu under it; else the menu closes.
                MenuResult.Close -> parent.let { back ->
                    parent = null
                    showMenu(back)
                }
            }
            return true
        }
        if (quick != null) return quickKey(e)
        val key = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> CalKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> CalKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> CalKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> CalKey.RIGHT
            // Guide p. 58: Space forward, Shift and Space back.
            KeyEvent.KEYCODE_SPACE -> if (e.isShiftPressed) CalKey.PREVIOUS else CalKey.NEXT
            else -> null
        }
        when {
            key != null -> go(screen.key(key, agenda.size))
            code == KeyEvent.KEYCODE_MENU -> if (loaded) menu(focused()).let { (entries, focus) -> showMenu(Menu(entries, highlighted = focus)) }
            click -> if (e.repeatCount == 0) click()
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE -> finish()
            else -> {
                val c = e.displayLabel
                if (!c.isLetterOrDigit()) return super.dispatchKeyEvent(e)
                // Quick Entry in the Day view (sim-227): the letter starts a new appointment's subject.
                if (quickEntry(screen.view, c)) {
                    quick = (e.unicodeChar.takeIf { it != 0 }?.toChar() ?: c).uppercaseChar().toString()
                    render()
                    return true
                }
                when (LETTERS[c.lowercaseChar()]) {
                    Letter.NEW -> chosen(Act.NEW)
                    Letter.AGENDA -> go(screen.show(CalView.AGENDA))
                    Letter.DAY -> go(screen.show(CalView.DAY))
                    Letter.WEEK -> go(screen.show(CalView.WEEK))
                    Letter.MONTH -> go(screen.show(CalView.MONTH))
                    Letter.TODAY -> today()
                    Letter.GO_TO_DATE -> chosen(Act.GO_TO_DATE)
                    null -> return super.dispatchKeyEvent(e)
                }
            }
        }
        return true
    }
}
