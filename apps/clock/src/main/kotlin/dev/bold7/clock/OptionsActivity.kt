package dev.bold7.clock

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Bundle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.bold7.design.DialogColors
import dev.bold7.design.ListColors
import dev.bold7.design.ListDimens
import dev.bold7.design.Typography
import dev.bold7.platform.hideStatusBar
import dev.bold7.ui.Bold7Dialog
import dev.bold7.ui.CheckBoxView
import dev.bold7.ui.ChoiceList
import dev.bold7.ui.ChoiceListView
import dev.bold7.ui.ChoiceView
import dev.bold7.ui.DialogIcon
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.TitleBar
import dev.bold7.ui.fillPaint
import dev.bold7.ui.fit
import dev.bold7.ui.textPaint
import dev.bold7.ui.themeImage
import dev.bold7.ui.withSwitchApplication

/**
 * Clock Options (sim-170 to sim-177, C), drawn as the trackpad's options screen (title
 * bar, group boxes and their grey titles, the choice fields, check box and buttons of core/ui) and
 * scrolled, since it has five groups: Clock (Clock Face, Home Time Zone, When Charging, Dismiss Clock
 * Automatically, Set Time), Alarm (Alarm
 * Tone, Volume, Snooze Time, Vibration, Try it), Bedside Mode (Dim Screen), Stopwatch (Stopwatch
 * Face), Countdown Timer (Timer Face, Timer Tone, Volume, Vibration, Try it) ([OptionRow]). Saved by
 * Menu > Save, or Save when leaving with changes ("Changes made!", sim-47), as every options screen
 * of OS 7. Try it plays the tone for five seconds at the volume chosen (D: the volume as a gain of
 * the alarm stream's own level, Escalating at full) and vibrates once if Vibration is On; it stops
 * with the screen. The groups leave out the settings Android does not let an app change.
 */
class OptionsActivity : Activity() {
    private enum class Act { SWITCH, SAVE, CLOSE }

    private val graph get() = application.graph
    private var saved = ClockSettings()
    private var settings = saved
    private var focused = 0
    private var list: ChoiceList? = null
    private var menu: Menu<Act>? = null

    private lateinit var views: List<View>
    private lateinit var scroll: ScrollView
    private lateinit var listView: ChoiceListView
    private lateinit var menuView: MenuView<Act>
    private var preview: MediaPlayer? = null

    private val rows = OptionRow.entries

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        views = rows.map { r ->
            val tap = View.OnClickListener { focused = r.ordinal; click() }
            when (r.kind) {
                Kind.CHOICE -> ChoiceView(this).apply { stacked = r == OptionRow.HOME_ZONE; setOnClickListener(tap) }
                Kind.CHECK -> CheckBoxView(this).apply { setOnClickListener(tap) }
                Kind.BUTTON -> ButtonRow(this).apply { setOnClickListener(tap) }
            }
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (g in OptionGroup.entries) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ListColors.Background)
                addView(GroupTitle(this@OptionsActivity, getString(groupTitle(g))), LinearLayout.LayoutParams(match, GroupTitle.HEIGHT))
            }
            rows.filter { it.group == g }.forEach { box.addView(views[it.ordinal], LinearLayout.LayoutParams(match, when { it.kind == Kind.BUTTON -> ButtonRow.HEIGHT; it == OptionRow.HOME_ZONE -> ChoiceView.STACKED_HEIGHT; else -> ChoiceView.HEIGHT })) }
            content.addView(box, LinearLayout.LayoutParams(match, wrap).apply { setMargins(5, 4, 5, 0) })
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ListColors.GroupBackground)
            addView(TitleBar(this@OptionsActivity, getString(R.string.options_title), options = true), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
            addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        }
        listView = ChoiceListView(this, onChoose = ::choose, onCancel = { showList(null) })
        menuView = MenuView(this, glyph = { null }, label = { getString(when (it) { Act.SWITCH -> R.string.act_switch_application; Act.SAVE -> R.string.act_save; Act.CLOSE -> R.string.act_close }) }, onEntry = ::chosen, onExpand = {}, onClose = { showMenu(null) })
        setContentView(FrameLayout(this).apply {
            addView(page, FrameLayout.LayoutParams(match, match))
            addView(listView, FrameLayout.LayoutParams(match, match))
            addView(menuView, FrameLayout.LayoutParams(match, match))
        })
        // No status bar: the 9900 shows its title bar alone (sim-170), as Tasks Options.
        window.hideStatusBar()
        render()
        graph.io.post {
            val read = graph.keep.settings
            mainExecutor.execute {
                saved = read
                settings = read
                render()
            }
        }
    }

    override fun onStop() {
        stopPreview()
        super.onStop()
    }

    private fun groupTitle(g: OptionGroup) = when (g) {
        OptionGroup.CLOCK -> R.string.section_clock
        OptionGroup.ALARM -> R.string.section_alarm
        OptionGroup.BEDSIDE -> R.string.section_bedside
        OptionGroup.STOPWATCH -> R.string.section_stopwatch
        OptionGroup.TIMER -> R.string.section_timer
    }

    private fun label(r: OptionRow) = getString(
        when (r) {
            OptionRow.FACE -> R.string.opt_face
            OptionRow.HOME_ZONE -> R.string.opt_home_zone
            OptionRow.WHEN_CHARGING -> R.string.opt_when_charging
            OptionRow.DISMISS -> R.string.opt_dismiss
            OptionRow.SET_TIME -> R.string.act_set_time
            OptionRow.TONE -> R.string.opt_alarm_tone
            OptionRow.VOLUME, OptionRow.TIMER_VOLUME -> R.string.opt_volume
            OptionRow.SNOOZE -> R.string.opt_snooze
            OptionRow.VIBRATION, OptionRow.TIMER_VIBRATION -> R.string.opt_vibration
            OptionRow.TRY_ALARM, OptionRow.TRY_TIMER -> R.string.opt_try_it
            OptionRow.DIM -> R.string.opt_dim
            OptionRow.STOPWATCH_FACE -> R.string.opt_stopwatch_face
            OptionRow.TIMER_FACE -> R.string.opt_timer_face
            OptionRow.TIMER_TONE -> R.string.opt_timer_tone
        },
    )

    private fun render() {
        for (r in rows) {
            val lit = focused == r.ordinal && list == null
            val label = label(r)
            when (val v = views[r.ordinal]) {
                is ChoiceView -> v.bind("$label:", shown(r, settings), lit)
                is CheckBoxView -> v.bind("$label:", checked(r, settings), lit)
                is ButtonRow -> v.bind(label, lit)
            }
        }
        reveal(views[focused])
    }

    /** The focused row scrolled into view, its group's title with it when it is the group's first (no animation). */
    private fun reveal(v: View) {
        if (scroll.height == 0) return
        val row = rows[focused]
        val head = if (rows.first { it.group == row.group } == row) v.parent as View else v
        val top = Rect().also { head.getDrawingRect(it) }.also { scroll.offsetDescendantRectToMyCoords(head, it) }.top
        val bottom = Rect().also { v.getDrawingRect(it) }.also { scroll.offsetDescendantRectToMyCoords(v, it) }.bottom
        if (top < scroll.scrollY) scroll.scrollTo(0, top) else if (bottom > scroll.scrollY + scroll.height) scroll.scrollTo(0, bottom - scroll.height)
    }

    /** The trackpad's click or a tap on the focused row. */
    private fun click() {
        val r = rows[focused]
        when (r.kind) {
            Kind.CHOICE -> openList()
            Kind.CHECK -> {
                settings = toggle(r, settings)
                render()
            }
            Kind.BUTTON -> when (r) {
                OptionRow.SET_TIME -> setTime()
                else -> tryIt(r == OptionRow.TRY_TIMER)
            }
        }
    }

    /** As the Clock's Set Time (ClockActivity.setTime): Options' Date and Time. */
    private fun setTime() {
        val options = Intent().setClassName("dev.bold7.launcher", "dev.bold7.launcher.options.OptionsActivity").putExtra("dev.bold7.options.SCREEN", "DATE_TIME").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(options)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Options not found", e)
        }
    }

    /** Try it: the tone of the group's ring as the fields show it now, saved or not. */
    private fun tryIt(timer: Boolean) {
        val spec = ringSpec(timer, settings)
        graph.io.post {
            stopPreview()
            if (spec.volume != SILENT) {
                val g = if (spec.volume == ESCALATING) 1f else spec.volume / 10f
                preview = openTone(this, spec.tone, g)
            }
            if (spec.vibrate) getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
                VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
            graph.io.postDelayed(::stopPreview, PREVIEW_MS)
        }
    }

    private fun stopPreview() {
        graph.io.removeCallbacks(::stopPreview)
        preview?.run {
            stop()
            release()
        }
        preview = null
    }

    private fun openList() {
        val row = views[focused]
        val at = IntArray(2).also { row.getLocationInWindow(it) }
        val origin = IntArray(2).also { listView.getLocationInWindow(it) }
        val (values, chosen) = choices(rows[focused], settings)
        val l = ChoiceList(values.size, chosen)
        list = l
        listView.open(values, l, at[0] - origin[0] + (row as ChoiceView).fieldLeft, at[1] - origin[1] + row.fieldTop)
        render()
    }

    private fun showList(l: ChoiceList?) {
        list = l
        listView.show(l)
        render()
    }

    private fun choose(index: Int) {
        settings = choose(rows[focused], settings, index)
        showList(null)
    }

    private fun showMenu(m: Menu<Act>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val act = menu?.entries?.getOrNull(index)?.action ?: return
        showMenu(null)
        when (act) {
            Act.SWITCH -> SwitchApplication.start(this)
            Act.SAVE -> save()
            Act.CLOSE -> leave()
        }
    }

    private fun leave() {
        if (settings == saved) return finish()
        Bold7Dialog(this, getString(R.string.changes_made), listOf(getString(R.string.act_save), getString(R.string.discard), getString(R.string.cancel)), DialogIcon.QUESTION, onButton = {
            when (it) {
                0 -> save()
                1 -> finish()
            }
        }).show()
    }

    private fun save() {
        val o = settings
        graph.io.post {
            // Only what Options edits: the alarm itself is Set Alarm's.
            val keep = graph.keep
            keep.settings = keep.settings.copy(options = o.options, timer = o.timer, display = o.display)
            applyCharging(this, o.display.charging)
            mainExecutor.execute {
                saved = o
                finish()
            }
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        val click = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER
        val back = e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_ESCAPE
        if (click && e.repeatCount > 0) return true
        val l = list
        if (l != null) {
            when {
                e.keyCode == KeyEvent.KEYCODE_DPAD_UP -> showList(l.step(down = false))
                e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> showList(l.step(down = true))
                click -> choose(l.chosen)
                back -> showList(null)
            }
            return true
        }
        val m = menu
        if (m != null) {
            val key = when {
                e.keyCode == KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                click -> MenuKey.CLICK
                back -> MenuKey.BACK
                e.keyCode == KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> return true
            }
            when (val r = m.key(key)) {
                is MenuResult.Show -> showMenu(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> showMenu(null)
            }
            return true
        }
        when {
            back -> leave()
            e.keyCode == KeyEvent.KEYCODE_MENU -> showMenu(Menu(listOf(MenuEntry(Act.SAVE), MenuEntry(Act.CLOSE)), highlighted = 0).withSwitchApplication(Act.SWITCH))
            e.keyCode == KeyEvent.KEYCODE_DPAD_UP -> { focused = (focused - 1).coerceAtLeast(0); render() }
            e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { focused = (focused + 1).coerceAtMost(rows.size - 1); render() }
            click -> click()
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    /** An options group's title: bold, grey, over a line (sim-170, C; as the trackpad options screen's). */
    private class GroupTitle(context: Context, private val title: String) : View(context) {
        companion object {
            const val HEIGHT = 43
        }

        // sim-170 (C): "Clock" is 69 x 17 there, Typography.SUBTITLE bold; the labels stay at TEXT.
        private val text = textPaint(Typography.SUBTITLE, Typography.BOLD, ListColors.GroupTitle)
        private val line = fillPaint(ListColors.Divider)

        override fun onDraw(canvas: Canvas) {
            canvas.drawText(text.fit(title, width - 2f * ListDimens.TEXT_X), ListDimens.TEXT_X.toFloat(), 32f, text)
            canvas.drawRect(0f, (HEIGHT - 1).toFloat(), width.toFloat(), HEIGHT.toFloat(), line)
        }
    }

    /**
     * A button alone on its row, centred (Set Time, Try it, sim-170, sim-171, C): border_button.png
     * (A) as wide as its label plus 20 each side, blue under the trackpad's focus. The launcher's
     * ButtonRowView is the same and internal to it: it goes to core/ui when a third screen needs it.
     */
    private class ButtonRow(context: Context) : View(context) {
        companion object {
            const val HEIGHT = 61
            private const val TOP = 6
            private const val RIGHT = 13
            private const val PAD = 20
            private const val BUTTON = 51
        }

        private val normal = themeImage(context, "ui_button")
        private val focus = themeImage(context, "ui_button_focus")
        private val text = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonText, align = Paint.Align.CENTER)
        private val textLit = textPaint(Typography.TEXT, Typography.REGULAR, DialogColors.ButtonTextFocused, align = Paint.Align.CENTER)
        private var label = ""
        private var lit = false

        fun bind(label: String, lit: Boolean) {
            if (label == this.label && lit == this.lit) return
            this.label = label
            this.lit = lit
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val wide = (text.measureText(label).toInt() + 2 * PAD).coerceAtMost(width - 2 * RIGHT)
            val left = (width - wide) / 2
            val right = left + wide
            (if (lit) focus else normal).apply { setBounds(left, TOP, right, TOP + BUTTON) }.draw(canvas)
            canvas.drawText(text.fit(label, (wide - 2f * PAD).coerceAtLeast(0f)), (left + right) / 2f, TOP + 36f, if (lit) textLit else text)
        }
    }
}

private const val PREVIEW_MS = 5_000L
