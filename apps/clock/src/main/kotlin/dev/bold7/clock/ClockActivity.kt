package dev.bold7.clock

import android.app.Activity
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.bold7.design.Typography
import dev.bold7.showcase.SHOWCASE_EVENT_SETTING
import dev.bold7.showcase.showcaseEvent
import dev.bold7.showcase.showcaseOn
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.SpinDialog
import dev.bold7.ui.slate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Clock of BBOS 7.1 (docs/BBOS7-VISUEL.md §28): the analog clock and its alarm bar (sim-153,
 * sim-161), the stopwatch (sim-165, sim-169), the countdown timer (D) and Bedside Mode (D), in one
 * black screen without the status bar, as the 9900 shows them; the full menu of each page
 * ([ClockScreen]). It renders and decides nothing: the pages' rules are [ClockScreen]'s, the alarm's
 * [Alarm] and [SetAlarm]'s, the watches' [Stopwatch] and [Countdown]'s; what it changes is written on
 * the io thread and handed to AlarmManager by [AlarmScheduler].
 *
 * [EXTRA_SET_ALARM] (Manage Connections' Alarm) opens Set Alarm on the clock.
 * When the next alarm Android holds is another app's (Android's Clock), a line at the bottom says
 * when (D): both clocks ring, neither touches the other's alarms.
 */
class ClockActivity : Activity() {
    companion object {
        const val EXTRA_SET_ALARM = "dev.bold7.clock.SET_ALARM"
        private const val LAUNCHER = "dev.bold7.launcher"
        private const val OPTIONS_SCREEN = "dev.bold7.options.SCREEN"
        /** Bedside Mode's Dim Screen (sim-172: on by default), the window's brightness (D). */
        const val BEDSIDE_BRIGHTNESS = 0.05f
    }

    private val graph get() = application.graph
    private lateinit var face: FaceView
    private lateinit var watch: WatchView
    private lateinit var other: TextView
    private lateinit var home: TextView
    private lateinit var menuView: MenuView<Act>

    private var screen = ClockScreen()
    private var settings = ClockSettings()
    private var snoozed = false
    private var stopwatch = Stopwatch()
    private var timer = Countdown(0)
    private var loaded = false
    private var menu: Menu<Act>? = null
    private var setAlarmOnLoad = false

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            render()
            readOtherAlarm()
        }
    }

    // The stopwatch and the timer while they run and show: once a frame for the hundredths.
    private val frame = object : Runnable {
        override fun run() {
            render()
            if (watchRunning()) watch.postOnAnimation(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        face = FaceView(this)
        watch = WatchView(this).apply { visibility = View.GONE }
        fun line() = TextView(this).apply {
            typeface = slate(Typography.REGULAR)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.SUBTITLE.toFloat())
            setTextColor(0xFF999999.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        other = line()
        home = line()
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = { i -> menu?.let { chosen(it.entries[i].action) } }, onExpand = { menu?.let { showMenu(it.expand()) } }, onClose = { showMenu(null) })
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(face, FrameLayout.LayoutParams(match, match))
            addView(watch, FrameLayout.LayoutParams(match, match))
            addView(other, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = 40 })
            addView(home, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = 80 })
            addView(menuView, FrameLayout.LayoutParams(match, match))
        })
        face.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_UP && face.barContains(e.x, e.y) && screen.page == Page.CLOCK) setAlarm(); true }
        watch.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) watch.buttonAt(e.x, e.y)?.let { b ->
                screen = screen.copy(focus = b)
                screen.click(watchRunning())?.let(::chosen)
            }
            true
        }
        graph.io.post {
            val images = ClockImages(this)
            mainExecutor.execute {
                face.images = images.face
                watch.images = images.watch
            }
        }
        opened(intent)
        graph.io.post { contentResolver.registerContentObserver(Settings.Global.getUriFor(SHOWCASE_EVENT_SETTING), false, story) }
    }

    override fun onDestroy() {
        graph.io.post { contentResolver.unregisterContentObserver(story) }
        super.onDestroy()
    }

    /** The video's alarm (bold7_showcase_event, the shell's): the ring screen opens for the showcase's alarm, nothing rings nor is stored. */
    private val story by lazy {
        object : ContentObserver(graph.io) {
            override fun onChange(selfChange: Boolean) {
                val ring = showcaseRing(showcaseEvent(this@ClockActivity), System.currentTimeMillis())
                mainExecutor.execute {
                    val next = nextRinging(graph.ringing, ring)
                    if (next == graph.ringing) return@execute
                    graph.ringing = next
                    if (next != null) startActivity(Intent(this@ClockActivity, RingActivity::class.java))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opened(intent)
    }

    private fun opened(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_SET_ALARM, false)) return
        intent.removeExtra(EXTRA_SET_ALARM)
        screen = screen.open(Page.CLOCK)
        if (loaded) setAlarm() else setAlarmOnLoad = true
    }

    override fun onStart() {
        super.onStart()
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        registerReceiver(tick, IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        })
        load()
    }

    override fun onStop() {
        unregisterReceiver(tick)
        watch.removeCallbacks(frame)
        super.onStop()
    }

    /** What the Clock keeps, read on the io thread. */
    private fun load() = graph.io.post {
        val keep = graph.keep
        val s = keep.settings
        val snooze = keep.snoozeUntil?.let { it > System.currentTimeMillis() } == true
        val w = keep.stopwatch
        val t = keep.timer
        mainExecutor.execute {
            settings = s
            snoozed = snooze
            stopwatch = w
            timer = t
            loaded = true
            show()
            if (setAlarmOnLoad) {
                setAlarmOnLoad = false
                setAlarm()
            }
        }
        readOtherAlarm()
    }

    /** Android's next alarm, when it is not ours (io thread). */
    private fun readOtherAlarm() = graph.io.post {
        val next = if (showcaseOn(this)) null else getSystemService(AlarmManager::class.java).nextAlarmClock
        val text = next?.takeIf { it.showIntent?.creatorPackage != packageName }?.let {
            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(it.triggerTime), ZoneId.systemDefault())
            getString(R.string.other_alarm, dayText(t.toLocalDate()) + " " + "%02d:%02d".format(t.hour, t.minute))
        }
        mainExecutor.execute {
            other.text = text
            other.visibility = if (text != null && screen.page == Page.CLOCK) View.VISIBLE else View.GONE
        }
    }

    /** Whether the page's watch has counted: a stopwatch past zero, a timer not back at its full duration. */
    private fun counted(): Boolean {
        val t = SystemClock.elapsedRealtime()
        return if (screen.page == Page.TIMER) timer.left(t) != timer.duration else stopwatch.elapsed(t) > 0
    }

    private fun watchRunning() = when (screen.page) {
        Page.STOPWATCH -> stopwatch.running
        Page.TIMER -> timer.running
        else -> false
    }

    /** The page, its views and its brightness. */
    private fun show() {
        val page = screen.page
        face.visibility = if (page == Page.CLOCK || page == Page.BEDSIDE) View.VISIBLE else View.GONE
        watch.visibility = if (page == Page.STOPWATCH || page == Page.TIMER) View.VISIBLE else View.GONE
        face.showBar = page == Page.CLOCK || page == Page.BEDSIDE
        face.bedside = page == Page.BEDSIDE
        face.requestLayout()
        other.visibility = if (page == Page.CLOCK && !other.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        face.kind = settings.display.face
        // Dim Screen (Bedside Mode, sim-172): the screen as dim as the page says, or left as it is.
        window.attributes = window.attributes.apply { screenBrightness = if (page == Page.BEDSIDE && settings.display.dim) BEDSIDE_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        if (page == Page.BEDSIDE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        watch.focus = screen.focus
        render()
        watch.removeCallbacks(frame)
        if (watchRunning()) watch.postOnAnimation(frame)
    }

    private fun render() {
        val now = LocalDateTime.now()
        face.bind(now.hour, now.minute, dayText(now.toLocalDate()), alarmText(settings.alarm), snoozed, dateText(now.toLocalDate()))
        // Home Time Zone: the time there under the face, on the clock page (guide p. 201; D for the place).
        val there = homeLine(settings.display.homeZone, ZoneId.systemDefault(), Instant.now())
        home.text = there
        home.visibility = if (there != null && screen.page == Page.CLOCK) View.VISIBLE else View.GONE
        val t = SystemClock.elapsedRealtime()
        when (screen.page) {
            Page.STOPWATCH -> watch.bindStopwatch(stopwatch.elapsed(t), stopwatch.laps, stopwatch.lapTimes(), stopwatch.running)
            Page.TIMER -> watch.bindTimer(timer.left(t), timer.running)
            else -> Unit
        }
    }

    // Actions

    private fun label(a: Act) = getString(
        when (a) {
            Act.SET_ALARM -> R.string.act_set_alarm
            Act.SET_TIME -> R.string.act_set_time
            Act.CANCEL_SNOOZE -> R.string.act_cancel_snooze
            Act.SEND_RESULTS -> R.string.act_send_results
            Act.BEDSIDE -> R.string.act_bedside
            Act.EXIT_BEDSIDE -> R.string.act_exit_bedside
            Act.STOPWATCH -> R.string.act_stopwatch
            Act.TIMER -> R.string.act_timer
            Act.CLOCK -> R.string.act_clock
            Act.START -> R.string.act_start
            Act.STOP -> R.string.act_stop
            Act.LAP -> R.string.act_lap
            Act.RESET -> R.string.act_reset
            Act.SET_TIMER -> R.string.act_set_timer
            Act.OPTIONS -> R.string.act_options
            Act.CLOSE -> R.string.act_close
            Act.SWITCH -> R.string.act_switch_application
        },
    )

    private fun showMenu(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(a: Act) {
        showMenu(null)
        val t = SystemClock.elapsedRealtime()
        when (a) {
            Act.SET_ALARM -> setAlarm()
            Act.SET_TIME -> setTime()
            Act.CANCEL_SNOOZE -> cancelSnooze()
            Act.SEND_RESULTS -> sendResults(t)
            Act.BEDSIDE -> go(Page.BEDSIDE)
            Act.EXIT_BEDSIDE, Act.CLOCK -> go(Page.CLOCK)
            Act.STOPWATCH -> go(Page.STOPWATCH)
            Act.TIMER -> go(Page.TIMER)
            Act.START, Act.STOP, Act.LAP, Act.RESET -> if (screen.page == Page.TIMER) timerAct(a, t) else stopwatchAct(a, t)
            Act.SET_TIMER -> SpinDialog(this, SetAlarm.timer(timer.duration), hideStatusBar = true) { s -> setTimer(timer.set(s.duration)) }.show()
            Act.OPTIONS -> open(Intent(this, OptionsActivity::class.java))
            Act.CLOSE -> finish()
            Act.SWITCH -> SwitchApplication.start(this)
        }
    }

    private fun go(page: Page) {
        screen = screen.open(page)
        show()
    }

    private fun open(intent: Intent) = try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "no activity for $intent", e)
    }

    /**
     * Set Time (guide p. 201, B): Options' Date and Time in the launcher, which holds
     * the right to write the time settings; its one extra only picks the screen, so the intent is no
     * risk to it. Android's page if Options is not there.
     */
    private fun setTime() {
        val options = Intent().setClassName(LAUNCHER, "$LAUNCHER.options.OptionsActivity").putExtra(OPTIONS_SCREEN, "DATE_TIME").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(options)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Options not found, Android's date page opens", e)
            open(Intent(Settings.ACTION_DATE_SETTINGS))
        }
    }

    /** Send Results (guide p. 204): a message type, then the text in that app's composer, which the user sends. */
    private fun sendResults(now: Long) {
        val text = resultsText(stopwatch.elapsed(now), stopwatch.laps, stopwatch.lapTimes())
        Bold7Dialog(this, getString(R.string.send_results), listOf(getString(R.string.type_text), getString(R.string.type_email), getString(R.string.cancel)), null, onButton = {
            when (it) {
                0 -> open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", text))
                1 -> open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_SUBJECT, getString(R.string.results_subject)).putExtra(Intent.EXTRA_TEXT, text))
            }
        }).show()
    }

    /** A sleeping alarm forgotten: its regular ring stays armed, only the snooze goes (D, as Set Alarm does it). */
    private fun cancelSnooze() {
        snoozed = false
        render()
        graph.io.post {
            graph.keep.snoozeUntil = null
            graph.arm()
        }
    }

    private fun setAlarm() {
        SpinDialog(this, SetAlarm.open(settings.alarm, listOf(R.string.repeat_on, R.string.repeat_weekdays, R.string.repeat_off).map(::getString)), hideStatusBar = true) { s ->
            settings = settings.copy(alarm = s.alarm)
            snoozed = false
            render()
            val saved = settings
            graph.io.post {
                val keep = graph.keep
                keep.settings = saved
                keep.snoozeUntil = null
                graph.arm()
            }
        }.show()
    }

    private fun stopwatchAct(a: Act, t: Long) {
        stopwatch = when (a) {
            Act.START -> stopwatch.start(t)
            Act.STOP -> stopwatch.stop(t)
            Act.LAP -> stopwatch.lap(t)
            else -> stopwatch.reset()
        }
        val w = stopwatch
        graph.io.post { graph.keep.stopwatch = w }
        show()
    }

    private fun timerAct(a: Act, t: Long) = setTimer(
        when (a) {
            Act.START -> timer.start(t)
            Act.STOP -> timer.stop(t)
            else -> timer.reset()
        },
    )

    /** The timer kept and its end handed to AlarmManager: it rings with the Clock closed (guide p. 204). */
    private fun setTimer(next: Countdown) {
        timer = next
        graph.io.post {
            graph.keep.timer = next
            graph.armTimer(next.endsAt)
        }
        show()
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
                MenuResult.Close -> showMenu(null)
            }
            return true
        }
        when {
            code == KeyEvent.KEYCODE_MENU -> if (loaded) {
                val (entries, focus) = screen.menu(watchRunning(), snoozed, counted())
                showMenu(Menu(entries, highlighted = focus))
            }
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                screen = screen.step(right = code == KeyEvent.KEYCODE_DPAD_RIGHT)
                watch.focus = screen.focus
            }
            click -> if (loaded && e.repeatCount == 0) screen.click(watchRunning())?.let(::chosen)
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE -> {
                val back = screen.back() ?: run {
                    finish()
                    return true
                }
                screen = back
                show()
            }
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }
}
