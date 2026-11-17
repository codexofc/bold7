package dev.bold7.launcher.home

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.provider.Settings
import dev.bold7.showcase.SHOWCASE_SETTING
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.util.Log
import dev.bold7.design.ChargingBolt
import dev.bold7.design.bannerDate
import dev.bold7.design.bannerTime
import dev.bold7.hub.data.ItemKind
import dev.bold7.launcher.profiles.Profile
import dev.bold7.platform.ShowcaseMask
import dev.bold7.platform.alarmSet
import dev.bold7.platform.networkName
import java.time.LocalDateTime

/**
 * What the banner shows, from Android, only while the home shows ([start] and [stop]): the
 * clock's minute tick, the battery's broadcasts, Telephony's callbacks and the
 * Wi-Fi's network callback, all delivered on the main thread, none polled. Each change makes a
 * new [BannerState] for [onState]. No permission but ACCESS_NETWORK_STATE (normal).
 */
internal class BannerSource(
    private val context: Context,
    /** Binder calls out of the main thread: the Wi-Fi's callbacks and level. */
    private val io: android.os.Handler,
    /** The showcase's carrier and alarm in place of the phone's, or null; read on each publish. */
    private val mask: () -> ShowcaseMask?,
    private val onState: (BannerState) -> Unit,
) {
    private val main = android.os.Handler(context.mainLooper)
    private var profile = Profile.NORMAL

    /** Each Wi-Fi network's level, on the main thread: the banner shows the best while one is left. */
    private val wifiLevels = HashMap<Network, Int>()

    private var state = BannerState()
    private var started = false
    private var counts: Map<ItemKind, Int> = emptyMap()
    private var appCounts: Map<String, Int> = emptyMap()

    // Telephony's and the Wi-Fi's raw values, folded into the state by publish().
    private var level = 0
    private var inService = false
    private var radioOff = false
    private var type = NetworkType.UNKNOWN
    private var wifiLevel: Int? = null
    private var battery = -1
    private var status = 0
    private var alarm = false

    private val telephony by lazy { context.getSystemService(TelephonyManager::class.java) }
    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private val wifi by lazy { context.getSystemService(WifiManager::class.java) }
    private val alarms by lazy { context.getSystemService(AlarmManager::class.java) }

    // The showcase turned on or off: the carrier's name and the alarm again.
    private val showcaseWatch = object : ContentObserver(android.os.Handler(context.mainLooper)) {
        override fun onChange(selfChange: Boolean) {
            readAlarm()
            publish()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) battery(intent)
            if (intent.action == AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) readAlarm()
            publish()
        }
    }

    private val phone = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener, TelephonyCallback.ServiceStateListener {
        override fun onSignalStrengthsChanged(s: SignalStrength) {
            level = s.level
            publish()
        }

        override fun onServiceStateChanged(s: ServiceState) {
            inService = s.state == ServiceState.STATE_IN_SERVICE
            radioOff = s.state == ServiceState.STATE_POWER_OFF
            publish()
        }
    }

    private val display = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
            type = networkType(info.networkType, info.overrideNetworkType)
            publish()
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        // On io: the level is a binder call too.
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val level = wifi.calculateSignalLevel(caps.signalStrength)
            main.post post@{
                if (!started) return@post
                wifiLevels[network] = level
                wifiLevel = wifiLevels.values.maxOrNull()
                publish()
            }
        }

        override fun onLost(network: Network) {
            main.post post@{
                if (!started) return@post
                wifiLevels.remove(network)
                wifiLevel = wifiLevels.values.maxOrNull()
                publish()
            }
        }
    }

    /** The Hub's unread counts (HubBadges.counts, and appCounts for the other apps). */
    fun counts(counts: Map<ItemKind, Int>, apps: Map<String, Int>) {
        if (counts == this.counts && apps == appCounts) return
        this.counts = counts
        appCounts = apps
        if (started) publish()
    }

    /** The selected profile (ProfileController), on the main thread. */
    fun profile(profile: Profile) {
        if (profile == this.profile) return
        this.profile = profile
        if (started) publish()
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        // System broadcasts reach a receiver that is not exported; the battery's is sticky.
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)?.let(::battery)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(SHOWCASE_SETTING), false, showcaseWatch)
        readAlarm()
        listen(phone, "signal")
        listen(display, "coverage")
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request, wifiCallback, io)
        publish()
    }

    fun stop() {
        if (!started) return
        started = false
        context.unregisterReceiver(receiver)
        context.contentResolver.unregisterContentObserver(showcaseWatch)
        telephony.unregisterTelephonyCallback(phone)
        telephony.unregisterTelephonyCallback(display)
        connectivity.unregisterNetworkCallback(wifiCallback)
        // The callback tells them all again at the next start.
        wifiLevels.clear()
        wifiLevel = null
    }

    // Neither needs a permission since Android 12; refused all the same, the banner goes without.
    private fun listen(callback: TelephonyCallback, what: String) {
        try {
            telephony.registerTelephonyCallback(context.mainExecutor, callback)
        } catch (e: SecurityException) {
            Log.w("Bold7/Home", "no $what in the banner: ${e.message}")
        }
    }

    // The next alarm clock is a binder call: read on io, published on main. Android's own, so a
    // snoozing alarm of Bold7's Clock (setAlarmClock) counts as the 9900's snooze indicator.
    private fun readAlarm() {
        io.post {
            val set = alarmSet(mask()) { alarms.nextAlarmClock != null }
            main.post post@{
                if (!started || set == alarm) return@post
                alarm = set
                publish()
            }
        }
    }

    private fun battery(intent: Intent) {
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        battery = if (raw < 0) -1 else raw * 100 / scale
        status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
    }

    private fun publish() {
        if (!started) return
        val now = LocalDateTime.now()
        val (time, amPm) = bannerTime(now, DateFormat.is24HourFormat(context))
        val next = BannerState(
            time = time,
            amPm = amPm,
            date = bannerDate(now),
            network = if (inService) networkName(mask()) { telephony.networkOperatorName.orEmpty() } else "",
            coverage = if (inService) coverage(type) else "",
            battery = batteryFrame(battery, status),
            charging = ChargingBolt.shows(status, battery),
            signal = signalFrame(level, inService, radioOff),
            wifi = wifiFrame(wifiLevel),
            profile = profileFrame(profile),
            alarm = alarm,
            indicators = indicators(counts, appCounts),
        )
        if (next == state) return
        state = next
        onState(next)
    }

    private fun networkType(type: Int, override: Int): NetworkType = when {
        override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA || override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> NetworkType.NR
        else -> when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS -> NetworkType.GPRS
            TelephonyManager.NETWORK_TYPE_EDGE -> NetworkType.EDGE
            TelephonyManager.NETWORK_TYPE_UMTS -> NetworkType.UMTS
            TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA -> NetworkType.HSPA
            TelephonyManager.NETWORK_TYPE_HSPAP -> NetworkType.HSPAP
            TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> NetworkType.LTE
            TelephonyManager.NETWORK_TYPE_NR -> NetworkType.NR
            else -> NetworkType.UNKNOWN
        }
    }
}
