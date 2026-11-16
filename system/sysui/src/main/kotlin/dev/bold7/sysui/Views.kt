package dev.bold7.sysui

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Handler
import dev.bold7.showcase.SHOWCASE_SETTING
import dev.bold7.showcase.Showcase
import dev.bold7.showcase.pick
import dev.bold7.showcase.showcaseOn
import android.os.Looper
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import dev.bold7.design.HomeBanner
import dev.bold7.design.HomeColors
import dev.bold7.design.HomeDimens
import dev.bold7.design.Typography
import dev.bold7.design.bannerDate
import dev.bold7.design.bannerTime
import java.time.LocalDateTime

private const val MODULE_PACKAGE = "dev.bold7.sysui"

/** The images of this APK by name (drawable-nodpi: drawn at their own pixel size), read once each. */
internal class Images(val res: Resources) {
    private val cache = HashMap<String, Drawable>()

    operator fun get(name: String): Drawable = cache.getOrPut(name) {
        res.getDrawable(res.getIdentifier(name, "drawable", MODULE_PACKAGE), null)
    }

    /** A string of this APK, by its R id: these resources are the module's own. */
    fun text(id: Int): String = res.getString(id)

    private var wrapper: Context? = null

    /**
     * SystemUI's application context with this APK's resources, for core/ui's views: they find their
     * theme images (ui_popup, ui_button…) by name, and core/ui keeps one image store per process
     * from the first context it is given. So the wrapper is made once, is its own application
     * context and answers to this package: the store neither holds a dialog's context nor looks
     * the APK's images up in SystemUI's own resources.
     */
    @Synchronized
    fun context(base: Context): Context = wrapper ?: object : ContextWrapper(base.applicationContext ?: base) {
        override fun getResources(): Resources = res
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = MODULE_PACKAGE
    }.also { wrapper = it }
}

/** Which of the 9900's banners: an app's (banner.pme, §18) or the lock screen's, the home's (lockscreen.pme, §10). */
internal enum class BannerKind { APP, LOCK }

/**
 * What the 9900's banner draws of its own (docs/BBOS7-VISUEL.md §18, places in [Banner]), from
 * Android's public callbacks: no polling, everything delivered on the main thread, registered only
 * while attached. Runs in SystemUI's process, which holds the phone and network permissions these
 * need.
 *
 * In an app's status bar ([BannerKind.APP]): the screen's title, else the roaming triangle and the
 * network's name, in the middle of the first line ([setTitle]), the coverage and the bars at its right edge, the Wi-Fi at the right of
 * the second, the counters in its middle ([setCounters]); Android's own views (battery, time,
 * status icons) sit around it (StatusBarLayout).
 *
 * On the lock screen ([BannerKind.LOCK], sim-64): the home's banner and indicator row whole, 720 x
 * 140, places of [HomeBanner]: its images, battery, date, network, time, coverage, bars, Wi-Fi,
 * the speaker of the profile in use (the launcher's PROFILE_SETTING, else the ringer mode) and the
 * counters, the battery, the time and the ringer from the system's own broadcasts (time ticks, the
 * battery's and the ringer's sticky ones), as the launcher's banner reads them. The counters, on
 * both, are the Hub's ([HubCounts]). No magnifier: the lock screen has none (sim-64).
 */
@SuppressLint("ViewConstructor", "MissingPermission")
internal class BannerView(
    context: Context,
    private val images: Images,
    private val kind: BannerKind,
    private val fail: (String, Exception) -> Unit,
) : View(context) {
    private var state = RadioState()
    private var operator: String? = null
    private var coverage: String? = null
    private var signal: Drawable? = null
    private var wifi: Drawable? = null
    private var roaming: Drawable? = null
    private var counters = emptyList<Counter>()

    /** A counter as drawn: its number, its icon and their widths, computed once per change. */
    private class Shown(val number: String, val numberWidth: Int, val icon: Drawable, val iconWidth: Int)
    private var shown = emptyList<Shown>()
    private var shownPlaces = emptyList<Pair<Int, Int>>()
    private var placesWidth = -1

    // --- An app's banner only: the call indicator and the Phone's one line ---

    /** TelephonyManager.CALL_STATE_*, AudioManager.isMicrophoneMute, and the test's demonstration (callDemo). */
    private var callState = 0
    private var micMuted = false
    private var callDemoImage: String? = null
    private var call: Drawable? = null

    /** The one-line banner of the Phone's screens (OneLine), else the two-line one. */
    private var oneLine = false

    /** The Calendar's one line ([BarKind.PLAIN_LINE]): no network's name. */
    private var plain = false

    /** The app in front and its screen's title (bannerTitle), for the two-line banner's middle. */
    private var titlePackage: String? = null
    private var titleLabel: CharSequence? = null
    private var row: OneLine.Places? = null
    private var timeX = OneLine.NAME_X

    /**
     * Where Android's views go in the one-line banner, the time's x and the battery's frame's x,
     * told when they change (on the main thread, posted: never inside a layout or a draw).
     */
    var onPlaces: ((timeX: Int, batteryX: Int) -> Unit)? = null
    private var told: Pair<Int, Int>? = null

    private val callWatch = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            callState = state
            showCall()
        }
    }

    private val micReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            micMuted = audio.isMicrophoneMute
            showCall()
        }
    }

    private val audio = context.getSystemService(AudioManager::class.java)

    private fun condensed(size: Int, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.create(Typography.FAMILY_CONDENSED, Typeface.NORMAL), Typography.REGULAR, false)
        textSize = size.toFloat()
        this.color = color
    }

    /** Network's name, coverage, the lock screen's date: BBAlpha Sans Condensed 27, white (sim-43, sim-64; Coverage of banner.pme). */
    private val text = condensed(Typography.Banner.sizePx, HomeColors.Text)

    /** A counter's number: GridIndicators font-family=BBAlpha Sans Condensed fill=white (banner.pme), 27 px like the time (sim-43). */
    private val count = condensed(Typography.APP_BAR_TIME, HomeColors.Text)

    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val radio = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener, TelephonyCallback.DataConnectionStateListener {
        override fun onSignalStrengthsChanged(s: SignalStrength) = update(state.copy(signalLevel = s.level))

        // Simplification: radio off stands for airplane mode, which turns it off; a radio switched off
        // otherwise (rare) shows OFF too, as the 9900 did. Emergency only is the public state
        // only, not the hidden isEmergencyOnly(). The names are system properties, read without
        // binder nor permission.
        override fun onServiceStateChanged(s: ServiceState) = update(state.copy(
            radio = when (s.state) {
                ServiceState.STATE_IN_SERVICE -> Radio.IN_SERVICE
                ServiceState.STATE_POWER_OFF -> Radio.AIRPLANE
                ServiceState.STATE_EMERGENCY_ONLY -> Radio.EMERGENCY_ONLY
                else -> Radio.NO_SERVICE
            },
            roaming = s.roaming,
        ).let(::operators))

        override fun onDataConnectionStateChanged(dataState: Int, networkType: Int) =
            update(state.copy(dataConnected = dataState == TelephonyManager.DATA_CONNECTED, networkType = networkType))
    }

    // Simplification: one Wi-Fi network at a time, as on a phone.
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(state.copy(wifiRssi = caps.signalStrength))
        override fun onLost(network: Network) = update(state.copy(wifiRssi = null))
    }

    // --- The lock screen's own: time, date, battery, speaker --------------------------------

    private val timePaint = condensed(Typography.BannerTime.sizePx, HomeColors.BannerTime).apply { textAlign = Paint.Align.CENTER }
    private val amPmPaint = condensed(Typography.BANNER_AM_PM, HomeColors.BannerTime)
    private var time = ""
    private var amPm = ""
    private var date = ""
    private var battery: Drawable? = null
    private var profile: Drawable? = null
    private var ringerMode = AudioManager.RINGER_MODE_NORMAL
    private var alarm: Drawable? = null

    // The launcher's profile (PROFILE_SETTING), told on the main thread. Settings.Global is cached in
    // this process and invalidated by the change itself: the read after a change is the only binder call.
    private val profileWatch = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = showProfile()
    }

    // The showcase turned on or off: the carrier's name and the alarm again, from where they come now.
    private val showcaseWatch = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            update(operators(state))
            if (kind == BannerKind.LOCK) {
                nextAlarm()
                invalidate()
            }
        }
    }

    // System broadcasts, protected: they reach a receiver that is not exported. Runs on SystemUI's
    // main thread, outside the hooks' guard: nothing may escape.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = system(intent)
    }

    init {
        update(state)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            telephony.registerTelephonyCallback(context.mainExecutor, radio)
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback, handler,
            )
        } catch (e: RuntimeException) {
            // SecurityException, IllegalStateException, TooManyRequestsException: no indicators,
            // SystemUI keeps running.
            fail("radio indicators: callbacks refused", e)
        }
        HubCounts.attach(this, lock = kind == BannerKind.LOCK)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(SHOWCASE_SETTING), false, showcaseWatch)
        if (kind == BannerKind.APP) {
            watchCall()
            return
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(PROFILE_SETTING), false, profileWatch)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        try {
            // Both sticky ones come back at once, the battery's first (registerReceiver returns
            // one of them only: the ringer's arrives through onReceive too).
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)?.let(::system)
            nextAlarm()
        } catch (e: RuntimeException) {
            fail("lock banner: system broadcasts refused, time, battery, speaker and alarm frozen", e)
        }
        clock()
    }

    /**
     * The call state (READ_PHONE_STATE, SystemUI's) and the microphone's mute, which Telecom sets
     * for a muted call (AudioManager.setMicrophoneMute, broadcast by AudioService). A refusal is
     * counted: no call indicator, the rest of the banner unchanged.
     */
    private fun watchCall() {
        try {
            telephony.registerTelephonyCallback(context.mainExecutor, callWatch)
            context.registerReceiver(micReceiver, IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            micMuted = audio.isMicrophoneMute
            showCall()
        } catch (e: RuntimeException) {
            fail("call indicator: callbacks refused", e)
        }
    }

    override fun onDetachedFromWindow() {
        HubCounts.detach(this)
        context.contentResolver.unregisterContentObserver(showcaseWatch)
        if (kind == BannerKind.LOCK) context.contentResolver.unregisterContentObserver(profileWatch)
        if (kind == BannerKind.APP) {
            telephony.unregisterTelephonyCallback(callWatch)
            try {
                context.unregisterReceiver(micReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "banner: the microphone's receiver was not registered", e)
            }
        }
        try {
            telephony.unregisterTelephonyCallback(radio)
            connectivity.unregisterNetworkCallback(wifiCallback)
            if (kind == BannerKind.LOCK) context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Registration had failed (counted then).
            Log.w(TAG, "banner: callbacks were not registered", e)
        }
        super.onDetachedFromWindow()
    }

    private fun system(intent: Intent) {
        try {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
                    // As the home's banner: the plug while charging or full (BannerSource).
                    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    battery = images[batteryImage(if (raw < 0) -1 else raw * 100 / scale, charging)]
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    ringerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
                    showProfile()
                }
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> nextAlarm()
                else -> clock()
            }
            invalidate()
        } catch (e: RuntimeException) {
            fail("lock banner: ${intent.action} unreadable", e)
        }
    }

    private fun clock() {
        val now = LocalDateTime.now()
        val (t, a) = bannerTime(now, DateFormat.is24HourFormat(context))
        time = t
        amPm = a
        date = bannerDate(now, context.resources.configuration.locales[0])
        if (profile == null) showProfile()
        invalidate()
    }

    // Main thread, from a broadcast or the setting's observer: nothing may escape into SystemUI.
    private fun showProfile() {
        try {
            profile = images[lockProfileImage(Settings.Global.getString(context.contentResolver, PROFILE_SETTING), ringerMode)]
        } catch (e: RuntimeException) {
            fail("lock banner: profile unreadable", e)
        }
        invalidate()
    }

    /**
     * The alarm indicator right of the battery while an alarm is set, snoozed included (sim-337,
     * sim-338): Android's next alarm clock, which Bold7's Clock sets for its alarm and its snooze
     * (setAlarmClock), as Android's own alarm icon reads it.
     */
    private fun nextAlarm() {
        val set = pick(showcaseOn(context), { context.getSystemService(AlarmManager::class.java).nextAlarmClock != null }, { Showcase.alarm.on })
        alarm = if (set) images["banner_alarm"] else null
    }

    /** [s] with the carrier's names: the showcase's neutral one while it is on (the real ones are not read), else the SIM's and the network's. */
    private fun operators(s: RadioState): RadioState = pick(
        showcaseOn(context),
        { s.copy(simOperator = telephony.simOperatorName, networkOperator = telephony.networkOperatorName) },
        { s.copy(simOperator = Showcase.CARRIER, networkOperator = Showcase.CARRIER) },
    )

    // Runs in the phone and network callbacks: nothing may escape into SystemUI.
    private fun update(new: RadioState) {
        state = new
        try {
            signal = images[signalImage(new)]
            wifi = wifiImage(new.wifiRssi)?.let(images::get)
            roaming = roamingImage(new)?.let(images::get)
        } catch (e: RuntimeException) {
            fail("radio indicators: no image for $new", e)
            return
        }
        operator = operatorName(new)
        coverage = coverageText(new)
        place()
        invalidate()
    }

    // Runs in the call callbacks: nothing may escape into SystemUI.
    private fun showCall() {
        val name = callDemoImage ?: callImage(callState, micMuted)
        call = try {
            name?.let(images::get)
        } catch (e: RuntimeException) {
            fail("call indicator: no image $name", e)
            null
        }
        placesWidth = -1
        place()
        invalidate()
    }

    /** The call indicator the test asks for (callDemo), null for the real call's. Main thread. */
    fun setCallDemo(image: String?) {
        if (image == callDemoImage) return
        callDemoImage = image
        showCall()
    }

    /** The one-line banner of [kind] (the Phone's, the Calendar's) or the two-line one. Main thread. */
    fun setLine(kind: BarKind) {
        val on = oneLineBanner(kind)
        val noName = kind == BarKind.PLAIN_LINE
        if (on == oneLine && noName == plain) return
        oneLine = on
        plain = noName
        told = null
        place()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place()

    /**
     * The one-line row and the time's place, again at each change of what they hold; the places
     * of Android's time and battery told to [onPlaces] when they move.
     */
    private fun place() {
        if (kind != BannerKind.APP || !oneLine || width == 0) return
        val name = if (plain) null else shownName()
        timeX = OneLine.timeX(name?.let { (roaming?.intrinsicWidth ?: 0) + text.measureText(it).toInt() })
        val r = OneLine.places(
            width, coverage?.let { text.measureText(it).toInt() }, wifi != null, call != null,
            shown.map { it.numberWidth }, shown.map { it.iconWidth },
        )
        row = r
        val places = timeX to r.batteryX
        if (places == told) return
        told = places
        post { onPlaces?.invoke(places.first, places.second) }
    }

    /** The network's name as the banner shows it, cut to NAME_WIDTH with the roaming triangle. */
    private fun shownName(): String? = operator?.let {
        TextUtils.ellipsize(it, text, (Banner.NAME_WIDTH - (roaming?.intrinsicWidth ?: 0)).toFloat(), TextUtils.TruncateAt.END).toString()
    }

    /** The screen's title ([bannerTitle]) of the app in front [pkg], [label] its task's label. Main thread. */
    fun setTitle(pkg: String?, label: CharSequence?) {
        if (pkg == titlePackage && TextUtils.equals(label, titleLabel)) return
        titlePackage = pkg
        titleLabel = label
        invalidate()
    }

    /** The two-line banner's middle: the screen's title, cut to NAME_WIDTH, or the network's name. */
    private fun shownTitle(): String? = bannerTitle(titlePackage, titleLabel, null)?.let {
        TextUtils.ellipsize(it, text, Banner.NAME_WIDTH.toFloat(), TextUtils.TruncateAt.END).toString()
    }

    /** The counters of the second line, or of the lock screen's indicator row, their icons at their own size. Main thread. */
    fun setCounters(counters: List<Counter>) {
        if (counters == this.counters) return
        shown = counters.map { c ->
            val number = c.count.toString()
            val icon = images[c.image]
            Shown(number, count.measureText(number).toInt(), icon, icon.intrinsicWidth)
        }
        placesWidth = -1
        this.counters = counters
        place()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(
        getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
        if (kind == BannerKind.LOCK) HOME_BANNER_HEIGHT else getDefaultSize(suggestedMinimumHeight, heightMeasureSpec),
    )

    override fun onDraw(canvas: Canvas) = if (kind == BannerKind.APP) drawBanner(canvas) else drawLock(canvas)

    private fun drawBanner(canvas: Canvas) {
        if (oneLine) return drawOneLine(canvas)
        val w = width
        val title = shownTitle()
        if (title == null) drawName(canvas, w, Banner.TOP_BASELINE) else canvas.drawText(title, (w - text.measureText(title)) / 2, Banner.TOP_BASELINE.toFloat(), text)
        // First line, right: the coverage, then the bars at the edge.
        val signalX = Banner.signalX(w)
        signal?.let { draw(canvas, it, signalX, Banner.SIGNAL_Y) }
        coverage?.let { canvas.drawText(it, Banner.coverageX(w, text.measureText(it).toInt()).toFloat(), Banner.TOP_BASELINE.toFloat(), text) }
        // Second line, right: the Wi-Fi under the bars.
        wifi?.let { draw(canvas, it, Banner.wifiX(w), Banner.WIFI_Y) }
        drawCounters(canvas, w)
    }

    /**
     * sim-109: in the bar's top 34 px, the triangle and the network's name from the left
     * (Android's time after them, StatusBarLayout), then from the right the bars, the coverage,
     * the Wi-Fi, the call, the counters (Android's battery before them).
     */
    private fun drawOneLine(canvas: Canvas) {
        val r = row ?: return
        if (!plain) drawName(canvas, OneLine.NAME_X, OneLine.HEIGHT, OneLine.BASELINE, centred = false)
        signal?.let { draw(canvas, it, r.signalX, OneLine.SIGNAL_Y) }
        coverage?.let { canvas.drawText(it, r.coverageX.toFloat(), OneLine.COVERAGE_BASELINE.toFloat(), text) }
        wifi?.let { draw(canvas, it, r.wifiX, OneLine.WIFI_Y) }
        call?.let { draw(canvas, it, r.callX, OneLine.FRAME_Y) }
        for ((c, xs) in shown.zip(r.counters)) {
            canvas.drawText(c.number, xs.first.toFloat(), OneLine.COUNT_BASELINE.toFloat(), count)
            draw(canvas, c.icon, xs.second, OneLine.COUNT_ICON_Y)
        }
    }

    /**
     * The triangle then the name: centred together within NAME_WIDTH on [w] (the two-line banner,
     * the lock screen's), or from [w] as their left edge ([centred] false, the one line), in a
     * line [line] px high.
     */
    private fun drawName(canvas: Canvas, w: Int, baseline: Int) = drawName(canvas, w, Banner.LINE, baseline, centred = true)

    private fun drawName(canvas: Canvas, w: Int, line: Int, baseline: Int, centred: Boolean) {
        val triangle = roaming
        val name = shownName()
        val nameWidth = name?.let { text.measureText(it) } ?: 0f
        var x = if (centred) (w - (triangle?.intrinsicWidth ?: 0) - nameWidth) / 2 else w.toFloat()
        triangle?.let {
            draw(canvas, it, x.toInt(), (line - it.intrinsicHeight) / 2)
            x += it.intrinsicWidth
        }
        name?.let { canvas.drawText(it, x, baseline.toFloat(), text) }
    }

    /** sim-64: the home's banner and row (sim-03), their backgrounds widened (all their columns alike). */
    private fun drawLock(canvas: Canvas) {
        val w = width
        val row = HomeDimens.BANNER
        wide(canvas, images["lock_banner_background"], 0)
        wide(canvas, images["lock_banner_divider"], row - 1)
        wide(canvas, images["lock_indicator_bar"], row)
        battery?.let { draw(canvas, it, HomeBanner.BATTERY_X, HomeBanner.BATTERY_Y) }
        alarm?.let { draw(canvas, it, HomeBanner.ALARM_X, HomeBanner.ALARM_Y) }
        canvas.drawText(date, HomeBanner.DATE_X.toFloat(), HomeBanner.DATE_BASELINE.toFloat(), text)
        drawName(canvas, w, HomeBanner.NETWORK_BASELINE)
        canvas.drawText(time, w / 2f, HomeBanner.TIME_BASELINE.toFloat(), timePaint)
        if (amPm.isNotEmpty()) canvas.drawText(amPm, w / 2f + timePaint.measureText(time) / 2 + HomeDimens.AM_PM_GAP, HomeBanner.TIME_BASELINE.toFloat(), amPmPaint)
        coverage?.let { canvas.drawText(it, w - HomeBanner.COVERAGE_RIGHT - text.measureText(it), HomeBanner.NETWORK_BASELINE.toFloat(), text) }
        signal?.let { draw(canvas, it, w - HomeBanner.SIGNAL_RIGHT, HomeBanner.SIGNAL_Y) }
        wifi?.let { draw(canvas, it, w - HomeBanner.WIFI_RIGHT, HomeBanner.WIFI_Y) }
        profile?.let { draw(canvas, it, HomeBanner.PROFILE_X, row + HomeBanner.ROW_ICON_Y) }
        for ((c, xs) in shown.zip(places(w))) {
            canvas.drawText(c.number, xs.first.toFloat(), (row + HomeBanner.COUNT_BASELINE).toFloat(), count)
            draw(canvas, c.icon, xs.second, row + HomeBanner.UNREAD_Y)
        }
    }

    /** A 640 px wide image of uniform columns across the whole width, at [top]. */
    private fun wide(canvas: Canvas, d: Drawable, top: Int) {
        d.setBounds(0, top, width, top + d.intrinsicHeight)
        d.draw(canvas)
    }

    /** Each counter: its number, then its icon; then the call's icon alone (D); centred together on the second line. */
    private fun drawCounters(canvas: Canvas, w: Int) {
        val iconY = Banner.LINE + (Banner.HEIGHT - Banner.LINE - Banner.COUNT_ICON) / 2
        val places = places(w)
        for ((c, xs) in shown.zip(places)) {
            canvas.drawText(c.number, xs.first.toFloat(), Banner.COUNT_BASELINE.toFloat(), count)
            c.icon.setBounds(xs.second, iconY, xs.second + c.iconWidth, iconY + Banner.COUNT_ICON)
            c.icon.draw(canvas)
        }
        call?.let { d -> places.getOrNull(shown.size)?.let { draw(canvas, d, it.second, Banner.CALL_Y) } }
    }

    /** The counters' places (and the call's, last, on an app's banner), computed again only when they or the width change. */
    private fun places(w: Int): List<Pair<Int, Int>> {
        if (w != placesWidth) {
            placesWidth = w
            val numbers = shown.map { it.numberWidth }
            val icons = shown.map { it.iconWidth }
            val withCall = kind == BannerKind.APP && call != null
            shownPlaces = if (kind == BannerKind.APP) {
                Banner.counterXs(w, if (withCall) numbers + null else numbers, if (withCall) icons + call!!.intrinsicWidth else icons)
            } else {
                rowCounterXs(w, numbers, icons)
            }
        }
        return shownPlaces
    }

    /** A theme image at its own size; images are shared (Images), their bounds set at each draw. */
    private fun draw(canvas: Canvas, d: Drawable, x: Int, y: Int) {
        d.setBounds(x, y, x + d.intrinsicWidth, y + d.intrinsicHeight)
        d.draw(canvas)
    }
}
