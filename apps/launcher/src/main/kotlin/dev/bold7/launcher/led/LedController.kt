package dev.bold7.launcher.led

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import dev.bold7.hub.data.HubContract
import dev.bold7.launcher.hub.HubReader
import dev.bold7.launcher.hub.hubBadges
import dev.bold7.launcher.options.LED_COVERAGE
import dev.bold7.launcher.options.NOTIFICATION_LIGHT_PULSE
import dev.bold7.launcher.profiles.PROFILE_SETTING
import dev.bold7.launcher.profiles.Profile

/**
 * Writes the colour of the LED (ledColour) to bold7_led, which bold7-led flashes. Its own
 * listeners, for the life of the process (the home's are only while it shows): the launcher is
 * the recents provider, bound by SystemUI, so it runs screen off too. Every input is an event
 * (provider and settings changes, the battery's broadcast, telephony and audio callbacks), all on
 * [thread]; the setting is written only when the colour changes. Started by the first home, once
 * the user unlocked (the Hub's provider is not readable before).
 */
class LedController(private val context: Context, private val hub: HubReader, private val thread: Handler) {
    private var started = false
    private var inputs = LedInputs(
        newInHub = false, messageLed = true, profile = null, ringing = false,
        batteryLow = false, plugged = false, inService = false, coverageLed = false,
    )
    private var written: LedColour? = null
    private val resolver get() = context.contentResolver

    private val settingsWatch = object : ContentObserver(thread) {
        override fun onChange(selfChange: Boolean) {
            readSettings()
            publish()
        }
    }

    private val hubWatch = object : ContentObserver(thread) {
        override fun onChange(selfChange: Boolean) {
            readHub()
            publish()
        }
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = battery(intent)
    }

    private val phone = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
        override fun onServiceStateChanged(s: ServiceState) {
            inputs = inputs.copy(inService = s.state == ServiceState.STATE_IN_SERVICE)
            publish()
        }
    }

    // MODE_RINGTONE while a call rings: no permission, unlike the call state.
    private val mode = AudioManager.OnModeChangedListener { m ->
        inputs = inputs.copy(ringing = m == AudioManager.MODE_RINGTONE)
        publish()
    }

    fun start() = thread.post {
        if (started) return@post
        started = true
        for (uri in listOf(
            Settings.System.getUriFor(NOTIFICATION_LIGHT_PULSE),
            Settings.Global.getUriFor(PROFILE_SETTING),
            Settings.Global.getUriFor(LED_COVERAGE),
        )) resolver.registerContentObserver(uri, false, settingsWatch)
        // Registering for another app's provider throws until it is installed (HubWatch).
        try {
            resolver.registerContentObserver(Uri.parse(HubContract.COUNTS_URI), false, hubWatch)
        } catch (e: SecurityException) {
            Log.w(TAG, "Hub changes not observed, no LED for new messages: ${e.javaClass.name}")
        }
        // System broadcasts reach a receiver that is not exported; the battery's is sticky.
        context.registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, thread, Context.RECEIVER_NOT_EXPORTED)?.let(::battery)
        try {
            context.getSystemService(TelephonyManager::class.java).registerTelephonyCallback({ thread.post(it) }, phone)
        } catch (e: SecurityException) {
            Log.w(TAG, "no coverage for the LED: ${e.message}")
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.addOnModeChangedListener({ thread.post(it) }, mode)
        inputs = inputs.copy(ringing = audio.mode == AudioManager.MODE_RINGTONE)
        readSettings()
        readHub()
        publish()
    }

    private fun battery(intent: Intent) {
        inputs = inputs.copy(
            batteryLow = intent.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        )
        publish()
    }

    private fun readSettings() {
        inputs = inputs.copy(
            messageLed = Settings.System.getString(resolver, NOTIFICATION_LIGHT_PULSE) != "0",
            profile = Profile.of(Settings.Global.getString(resolver, PROFILE_SETTING)),
            coverageLed = Settings.Global.getString(resolver, LED_COVERAGE) == "1",
        )
    }

    // Simplification: the whole Hub read at each of its changes, as the home does when it shows; a count
    // of its own in the provider if it ever shows in the battery.
    private fun readHub() {
        val s = hub.read()
        inputs = inputs.copy(newInHub = s != null && hubBadges(s.accounts, s.items, s.smsApp, s.dialer).hub)
    }

    private fun publish() {
        if (!started) return
        val colour = ledColour(inputs)
        if (colour == written) return
        try {
            if (Settings.Global.putString(resolver, LED_SETTING, colour.value)) written = colour
        } catch (e: SecurityException) {
            Log.w(TAG, "bold7_led not written: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "Bold7/Led"
    }
}
