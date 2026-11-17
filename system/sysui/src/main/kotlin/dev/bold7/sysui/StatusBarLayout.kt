package dev.bold7.sysui

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.KeyguardManager
import android.app.TaskStackListener
import android.graphics.Typeface
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import dev.bold7.design.HomeColors
import dev.bold7.design.Typography
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

// The SystemUI classes StatusBarLayout reaches by name (the module's class loader cannot see
// them). StatusBarTest finds each in stubs/systemui.jar, StatusBarTargets.kt compiles
// the members.
internal const val CLOCK_VIEW = "com.android.systemui.statusbar.policy.Clock"
internal const val COMMAND_QUEUE = "com.android.systemui.statusbar.CommandQueue"
internal const val BAR_BACKGROUND = "com.android.systemui.statusbar.phone.BarTransitions\$BarBackgroundDrawable"
internal const val HEADS_UP_APPEARANCE = "com.android.systemui.statusbar.phone.HeadsUpAppearanceController"
internal const val SURFACES_CALLBACKS = "com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks"

/**
 * The 9900's banner over an app (docs/BBOS7-VISUEL.md §18, places in [Banner]) in
 * PhoneStatusBarView, 67 px high by the framework overlay: on the first line the battery at the
 * left, on the second the time at the left and Android's status icons at the right, before the
 * Wi-Fi; BannerView draws the rest (network's name, coverage, bars, Wi-Fi, counters). Android
 * keeps every view and its logic (time, icons, battery, what hides them): this only moves them,
 * once, after PhoneStatusBarView.onFinishInflate, before CollapsedStatusBarFragment finds them by
 * id. Three hooks feed it ([SysuiModule.installStatusBar]). None may send Android down a path this
 * SystemUI never takes: forcing Android's own carrier name did (its ViewStub inflates a frame that
 * CollapsedStatusBarFragment casts to OperatorNameView).
 */
internal class StatusBarLayout(private val fail: (String, Exception?) -> Unit) {
    private var bar = WeakReference<ViewGroup>(null)
    private var clock = WeakReference<TextView>(null)
    private var battery = WeakReference<View>(null)
    private var statusIcons = WeakReference<View>(null)
    private var banner = WeakReference<BannerView>(null)
    /** Which banner shows (BarKind): the two-line one, or a one-line one (OneLine); main thread only. */
    private var kind = BarKind.TWO_LINES
    /** Told where the battery goes, true on a one-line bar: the charging bolt's place (BatteryBolt). */
    var batteryLine: (oneLine: Boolean) -> Unit = {}
    private var clockX = Banner.TIME_X
    private var clockBaseline = Banner.TIME_BASELINE
    private var layoutBroken = false
    private var overrideAlpha: Field? = null
    /** Main thread only. */
    private var shown = BarBackground.BLACK
    private var backgroundBroken = false
    /** The bar is hidden (barHidden): written on binder threads, read on the main thread. */
    @Volatile private var hiddenNow = false
    private var windowManager: Any? = null
    private var hideTransientBars: Method? = null
    private var loggedHidden = false
    private var tasksRefused = false

    /** The power key's dialog shows (PowerMenuScreen): the bar stays as it was under it (barKept). */
    @Volatile var powerMenuShown = false

    /** The app that sets the bar's look now, for the title events below; written on binder threads. */
    @Volatile private var controlling: String? = null
    private var listening = false

    /**
     * The title of a screen arrives after its window does (Activity.setTaskDescription is asked
     * once the activity runs), too late for the read at the bar's change. The task stack's own
     * events bring it, no polling: the task description changed, or the task came to the front.
     */
    private val taskEvents = object : TaskStackListener() {
        override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) = titled(taskInfo)
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) = titled(taskInfo)
    }

    // On a binder thread: nothing may escape into SystemUI.
    private fun titled(task: ActivityManager.RunningTaskInfo) {
        val pkg = task.topActivity?.packageName ?: return
        if (!pkg.startsWith("dev.bold7.")) return
        val label = task.taskDescription?.label
        // Kept for the window that comes after its title (onBarAttributes), shown now for the one in front.
        if (label == null) labels.remove(pkg) else labels[pkg] = label
        if (pkg != controlling || kind != BarKind.TWO_LINES) return
        bar.get()?.post { banner.get()?.setTitle(pkg, label) }
    }

    /** The latest label each app's task told, by package. */
    private val labels = java.util.concurrent.ConcurrentHashMap<String, CharSequence>()

    private fun listen() {
        if (listening) return
        listening = true
        try {
            ActivityTaskManager.getInstance().registerTaskStackListener(taskEvents)
        } catch (e: RuntimeException) {
            fail("status bar: the titles of the screens are not told", e)
        }
    }

    /** Rearranges the status bar around [banner], or leaves it whole when a view is missing ([find] counts it). */
    fun arrange(bar: ViewGroup, banner: BannerView, find: (String) -> View?): Boolean {
        val views = listOf(
            "status_bar_start_side_content", "status_bar_end_side_content", "system_icons", "statusIcons", "battery", "clock",
        ).map { find(it) ?: return false }
        val startContent = views[0]
        val endContent = views[1]
        val systemIcons = views[2] as? ViewGroup
        val statusIcons = views[3]
        val battery = views[4]
        val clock = views[5] as? TextView
        if (bar !is FrameLayout || systemIcons == null || clock == null) {
            fail("view target missing: status bar views of another type than expected in ${bar.javaClass.name}", null)
            return false
        }
        this.bar = WeakReference(bar)
        this.clock = WeakReference(clock)
        this.battery = WeakReference(battery)
        this.statusIcons = WeakReference(statusIcons)
        this.banner = WeakReference(banner)
        kind = BarKind.TWO_LINES
        clockX = Banner.TIME_X
        clockBaseline = Banner.TIME_BASELINE
        layoutBroken = false
        banner.onPlaces = { timeX, batteryX -> guardedLayout { if (oneLineBanner(kind)) placeOneLine(timeX, batteryX) } }
        // A re-inflated bar comes with new BarTransitions, black: the next attributes decide.
        shown = BarBackground.BLACK
        backgroundBroken = false

        // The start side's own content goes: notification icons (the counters are the Hub's,
        // HubCounts), heads-up text, call chip. Android never sets its visibility (only StatusBarBoundsProvider
        // reads its bounds).
        startContent.visibility = View.GONE
        bar.addView(banner, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Second line, left: the time.
        (clock.parent as ViewGroup).removeView(clock)
        bar.addView(clock, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.TOP))
        styleClock(clock)

        // First line, left: the battery, its frame where the 9900's is.
        systemIcons.removeView(battery)
        bar.addView(battery, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.TOP).apply {
            leftMargin = Banner.BATTERY_X
            topMargin = Banner.BATTERY_Y
        })

        // Second line, right: Android's status icons (alarm, profile, Bluetooth, GPS, NFC...),
        // their right edge 10 px before the Wi-Fi, their middle the Wi-Fi's.
        systemIcons.removeView(statusIcons)
        bar.addView(statusIcons, FrameLayout.LayoutParams(WRAP_CONTENT, Banner.ICONS_HEIGHT, Gravity.END or Gravity.TOP).apply {
            topMargin = Banner.ICONS_Y
            rightMargin = Banner.ICONS_RIGHT
        })
        statusIcons.setPaddingRelative(0, 0, 0, 0)
        iconSpacing(statusIcons, fail, Banner.SPACING)

        // Android hides the system info (keyguard, shade, bouncer) through the end side content
        // only, by its visibility (setVisibility(INVISIBLE), unanimated) or its alpha (animateShow):
        // what was taken out of it follows it, else it shows under the keyguard bar's own. The
        // Calendar's line has no battery (sim-220): it stays clear there.
        val followers = arrayOf(battery, statusIcons, banner)
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            // Android pads the bar left and right (PhoneStatusBarView.updateLayoutForCutout, from
            // StatusBarContentInsetsProvider): 16 px at the right on the Q25, the privacy dot's
            // ongoing_appops_dot_min_padding. The banner's places are from the screen's edges
            // (sim-43): the padding goes before the frame is drawn, the draw is cancelled and laid
            // out again (once per padding Android sets: at attach, on rotation or a new cutout).
            // The dot keeps its own corner window (ScreenDecorations), sized from the provider.
            if (bar.paddingLeft != 0 || bar.paddingRight != 0) {
                bar.setPadding(0, bar.paddingTop, 0, bar.paddingBottom)
                return@OnPreDrawListener false
            }
            for (v in followers) {
                v.visibility = endContent.visibility
                v.alpha = if (v === battery && kind == BarKind.PLAIN_LINE) 0f else endContent.alpha
            }
            true
        }
        // Registered while the bar is in its window only: the window's observer outlives a bar
        // that SystemUI inflates again.
        bar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = v.viewTreeObserver.addOnPreDrawListener(preDraw)
            override fun onViewDetachedFromWindow(v: View) = v.viewTreeObserver.removeOnPreDrawListener(preDraw)
        })
        if (bar.isAttachedToWindow) bar.viewTreeObserver.addOnPreDrawListener(preDraw)
        return true
    }

    /** BBAlpha Sans Condensed 27, #F7F7F7, its baseline on the second line. Again if Android resets the size. */
    private fun styleClock(clock: TextView) {
        clock.typeface = Typeface.create(Typeface.create(Typography.AppBarTime.family, Typeface.NORMAL), Typography.REGULAR, false)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, Typography.AppBarTime.sizePx.toFloat())
        clock.includeFontPadding = false
        clock.setPaddingRelative(0, 0, 0, 0)
        (clock.layoutParams as ViewGroup.MarginLayoutParams).apply {
            leftMargin = clockX
            topMargin = clockBaseline + clock.paint.fontMetricsInt.ascent
        }
        clock.requestLayout()
    }

    fun clockTarget(loader: ClassLoader): Method = loader.loadClass(CLOCK_VIEW).getDeclaredMethod("getSmallTime")

    /**
     * After Clock.getSmallTime: for the status bar's clock only (the shade header has one too),
     * Android's text in the banner's #F7F7F7 (fill=F7F7F7, banner.pme), over the tint Android sets
     * on the clock. AM/PM is Android's: the status bar's clock leaves it out.
     */
    fun timeText(view: TextView, android: CharSequence?): CharSequence? {
        if (view !== clock.get() || android == null) return android
        if (view.textSize != Typography.AppBarTime.sizePx.toFloat()) styleClock(view) // Clock.onDensityOrFontScaleChanged
        return SpannableString(android).apply {
            setSpan(ForegroundColorSpan(HomeColors.BannerTime), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun headsUpHideTarget(loader: ClassLoader): Method =
        // The third parameter is an R8 lambda class: found by name and arity.
        loader.loadClass(HEADS_UP_APPEARANCE).declaredMethods.firstOrNull { it.name == "hide" && it.parameterCount == 3 }
            ?: throw NoSuchMethodException("$HEADS_UP_APPEARANCE.hide, 3 parameters")

    /**
     * Instead of HeadsUpAppearanceController.hide: true (Android's hide skipped) for the status
     * bar's time, which a pinned notification fades out on Android, to put the notification's
     * text in its place (HeadsUpStatusBarView). That text lives in the start side content,
     * hidden here: without this, the time would go and nothing would replace it. The card itself
     * lies under the bar (mHeadsUpInset: the bar's height plus heads_up_status_bar_padding), so
     * nothing overlaps. Android's later show() of the clock, still visible, is
     * CrossFadeHelper.fadeIn: it only animates the alpha to 1, where it already is.
     */
    fun keptShown(view: View?): Boolean = view != null && view === clock.get()

    fun barAttributesTarget(loader: ClassLoader): Method {
        overrideAlpha = loader.loadClass(BAR_BACKGROUND).getField("mOverrideAlpha")
        // Its parameters include framework types hidden from the module's compile classpath.
        return loader.loadClass(COMMAND_QUEUE).declaredMethods.firstOrNull { it.name == "onSystemBarAttributesChanged" && it.parameterCount == 8 }
            ?: throw NoSuchMethodException("$COMMAND_QUEUE.onSystemBarAttributesChanged, 8 parameters")
    }

    /**
     * After CommandQueue.onSystemBarAttributesChanged, on a binder thread: which app sets the bar's
     * look, and so which bar shows (barKind) and its title (bannerTitle: the label of the task in
     * front, read here, once per change of the window in front). On the home, over an app with no
     * bar and under the lock screen (barHidden), the bar's whole window is drawn transparent:
     * whatever shows it there (a swipe from the top, a heads-up card, a dialog, a system event, the
     * lock screen going), nothing of Android shows. The switch (bold7_android_shade) is read here, at each change. Under the power key's dialog
     * nothing changes (barKept).
     */
    fun onBarAttributes(displayId: Int, controllingPackage: String?) {
        if (displayId != Display.DEFAULT_DISPLAY || barKept(controllingPackage, powerMenuShown)) return
        val bar = bar.get() ?: return
        controlling = controllingPackage
        listen()
        val look = barBackground(controllingPackage)
        val android = androidShadeAsked(Settings.Global.getInt(bar.context.contentResolver, ANDROID_SHADE_SETTING, 0))
        val demo = callDemo(Settings.Global.getInt(bar.context.contentResolver, CALL_DEMO_SETTING, 0))
        val hidden = barHidden(controllingPackage, android, keyguardLocked(bar))
        hiddenNow = hidden
        val kind = barKind(controllingPackage)
        val label = if (kind == BarKind.TWO_LINES && controllingPackage?.startsWith("dev.bold7.") == true) (taskLabel(bar, controllingPackage) ?: labels[controllingPackage]) else null
        bar.post {
            showBackground(bar, look, controllingPackage)
            showBar(bar, hidden)
            guardedLayout { showLines(kind, demo, controllingPackage, label) }
        }
    }

    /** Whether the lock screen shows (KeyguardManager, a binder call on the bar's binder thread). */
    private fun keyguardLocked(bar: View): Boolean = bar.context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /**
     * The label the task in front set for its screen (Activity.setTaskDescription; null when it set
     * none), if [pkg] is its app. A binder call (REAL_GET_TASKS, SystemUI's), on the binder thread
     * of the bar's attributes. A refusal is said once: no title, the network's name instead.
     */
    @Suppress("DEPRECATION") // getRunningTasks: the public read of TaskInfo.taskDescription, all SystemUI needs.
    private fun taskLabel(bar: View, pkg: String): CharSequence? = try {
        bar.context.getSystemService(ActivityManager::class.java).getRunningTasks(1).firstOrNull()
            ?.takeIf { it.topActivity?.packageName == pkg }?.taskDescription?.label
    } catch (e: RuntimeException) {
        if (!tasksRefused) Log.w(TAG, "status bar: the task in front is unreadable, no title", e)
        tasksRefused = true
        null
    }

    /**
     * The one-line banner under the Phone and the Calendar ([kind]), the two-line one elsewhere,
     * with the title of the screen of [pkg] ([label]): BannerView draws its part, Android's time,
     * battery and status icons move to their places (placeOneLine, once the banner has measured
     * its row). [demo] the test's call indicator (callDemo).
     */
    private fun showLines(kind: BarKind, demo: String?, pkg: String?, label: CharSequence?) {
        val banner = banner.get() ?: return
        banner.setCallDemo(demo)
        banner.setTitle(pkg, label)
        if (kind == this.kind) return
        val wasOne = oneLineBanner(this.kind)
        this.kind = kind
        banner.setLine(kind)
        Log.i(TAG, "status bar: $kind")
        if (oneLineBanner(kind) || !wasOne) return
        clockX = Banner.TIME_X
        clockBaseline = Banner.TIME_BASELINE
        clock.get()?.let(::styleClock)
        battery.get()?.let { margins(it, Banner.BATTERY_X, Banner.BATTERY_Y) }
        batteryLine(false)
        statusIcons.get()?.let { icons ->
            (icons.layoutParams as FrameLayout.LayoutParams).apply {
                height = Banner.ICONS_HEIGHT
                topMargin = Banner.ICONS_Y
                rightMargin = Banner.ICONS_RIGHT
            }
            icons.requestLayout()
        }
    }

    /** sim-109: the time after the network's name, the battery's frame at [batteryX], Android's status icons before it (D). */
    private fun placeOneLine(timeX: Int, batteryX: Int) {
        clockX = timeX
        clockBaseline = OneLine.BASELINE
        clock.get()?.let(::styleClock)
        battery.get()?.let { margins(it, batteryX, OneLine.FRAME_Y) }
        batteryLine(true)
        val width = bar.get()?.width ?: return
        statusIcons.get()?.let { icons ->
            (icons.layoutParams as FrameLayout.LayoutParams).apply {
                height = OneLine.HEIGHT
                topMargin = 0
                rightMargin = width - OneLine.iconsRight(batteryX)
            }
            icons.requestLayout()
        }
    }

    private fun margins(view: View, left: Int, top: Int) {
        (view.layoutParams as ViewGroup.MarginLayoutParams).apply {
            leftMargin = left
            topMargin = top
        }
        view.requestLayout()
    }

    // Posted on the main thread, outside the hooks' guard: a failure is counted and the bar's
    // layout stays as it is until the bar is inflated again.
    private inline fun guardedLayout(block: () -> Unit) {
        if (layoutBroken) return
        try {
            block()
        } catch (e: RuntimeException) {
            layoutBroken = true
            fail("status bar: one or two lines", e)
        }
    }

    // Runs posted on the main thread, outside the hook's guard: nothing may escape into SystemUI.
    private fun showBar(bar: ViewGroup, hidden: Boolean) {
        val root = bar.rootView
        val alpha = if (hidden) 0f else 1f
        if (root.alpha == alpha) return
        root.alpha = alpha
        if (hidden && !loggedHidden) {
            loggedHidden = true
            Log.i(TAG, "status bar: hidden (home, or an app without a bar)")
        }
    }

    /** CentralSurfacesCommandQueueCallbacks.showTransient(IIZ)V, and the window manager's hideTransientBars(I)V that undoes it. */
    fun transientTarget(loader: ClassLoader): Method {
        val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
            ?: throw NoSuchMethodException("WindowManagerGlobal.getWindowManagerService: null")
        hideTransientBars = Class.forName("android.view.IWindowManager").getMethod("hideTransientBars", Int::class.javaPrimitiveType)
        windowManager = wm
        return loader.loadClass(SURFACES_CALLBACKS).getDeclaredMethod("showTransient", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    }

    /**
     * After SystemUI showed transient bars, on its main thread: where the bar is hidden (barHidden),
     * hidden again at once, as its auto hide does after a few seconds (AutoHideController):
     * transparent, the bar would still take the touches of the screen's top meanwhile, and pull
     * Android's shade. On the home the swipe itself goes on to the launcher, which opens BBOS 7's
     * list of notifications.
     */
    fun onTransient(displayId: Int, types: Int) {
        if (!transientUndone(hiddenNow, displayId, types)) return
        hideTransientBars!!.invoke(windowManager, displayId)
    }

    /**
     * Over the keyguard, Android's bar background (BarTransitions, black by the overlay) fades to
     * nothing (this window lies under the keyguard's, which draws the keyguard bar: its black
     * showed through); back to Android's anywhere else. Android never writes the status bar
     * background's override alpha (only the navigation bar's).
     */
    // Runs posted on the main thread, outside the hook's guard: nothing may escape into SystemUI.
    private fun showBackground(bar: ViewGroup, look: BarBackground, controllingPackage: String?) {
        if (look == shown || backgroundBroken) return
        try {
            val background = generateSequence(bar.parent as? View) { it.parent as? View }
                .mapNotNull { it.background }.firstOrNull { it.javaClass.name == BAR_BACKGROUND }
            if (background == null) {
                backgroundBroken = true
                return fail("view target missing: $BAR_BACKGROUND above ${bar.javaClass.name}", null)
            }
            overrideAlpha!!.setFloat(background, barAlpha(look))
            background.invalidateSelf()
            shown = look
            Log.i(TAG, "status bar background: $look under $controllingPackage")
        } catch (e: ReflectiveOperationException) {
            backgroundBroken = true
            fail("status bar background", e)
        } catch (e: RuntimeException) {
            backgroundBroken = true
            fail("status bar background", e)
        }
    }
}
