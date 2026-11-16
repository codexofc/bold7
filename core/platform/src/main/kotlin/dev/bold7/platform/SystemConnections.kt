package dev.bold7.platform

import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Handler
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Android's switches as a priv-app of the module may turn them (read in the Q25's own dex): no su, no hook, no signature permission.
 * - Mobile network: the radio itself, as OS 7's box (calls too), by
 *   TelephonyManager.requestRadioPowerOffForReason(RADIO_POWER_REASON_USER) and its clear (hidden,
 *   MODIFY_PHONE_STATE, signature|privileged|role). Kept by the phone process until a reboot; an
 *   emergency call clears it.
 * - Wi-Fi: WifiManager.setWifiEnabled, allowed to a system app (FLAG_SYSTEM) with CHANGE_WIFI_STATE
 *   (normal); refused by Android in its airplane mode or with the hotspot on.
 * - Bluetooth: BluetoothAdapter.enable and disable, allowed to a system app holding
 *   BLUETOOTH_CONNECT (runtime, granted by tools/install.sh).
 * - NFC: NfcAdapter.enable and disable (hidden), WRITE_SECURE_SETTINGS (signature|privileged).
 * Android's airplane mode (ConnectivityManager.setAirplaneMode) wants NETWORK_AIRPLANE_MODE,
 * NETWORK_SETTINGS or NETWORK_STACK, all signature on the Q25: never turned, only read.
 *
 * The state comes from broadcasts and Telephony's service state on [io], read there (binder calls)
 * and handed to the main thread.
 */
class SystemConnections(
    private val context: Context,
    private val io: Handler,
    /** Read on [io] at each publish: the showcase's carrier and alarm in place of the phone's, or null. */
    private val mask: () -> ShowcaseMask? = { null },
) : ConnectionSwitches {
    private val main = Handler(context.mainLooper)
    private val telephony by lazy { context.getSystemService(TelephonyManager::class.java) }
    private val wifi by lazy { context.getSystemService(WifiManager::class.java) }
    private val bluetooth: BluetoothAdapter? by lazy { context.getSystemService(BluetoothManager::class.java)?.adapter }
    private val nfc: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(context) }
    private val alarms by lazy { context.getSystemService(AlarmManager::class.java) }

    override val available: Set<Connection> by lazy {
        val pm = context.packageManager
        buildSet {
            // Not FEATURE_TELEPHONY_RADIO_ACCESS: the Q25 does not declare it (seen on the device), yet its radio switches.
            if (pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) add(Connection.MOBILE)
            if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI)) add(Connection.WIFI)
            if (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) add(Connection.BLUETOOTH)
            if (pm.hasSystemFeature(PackageManager.FEATURE_NFC)) add(Connection.NFC)
        }
    }

    // Set on the main thread only: a reading posted before stop() is dropped.
    private var listener: ((ConnectionReadings) -> Unit)? = null
    private var inService = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = publish()
    }

    private val phone = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
        override fun onServiceStateChanged(s: ServiceState) {
            radioOn = s.state != ServiceState.STATE_POWER_OFF
            inService = s.state == ServiceState.STATE_IN_SERVICE
            publish()
        }
    }
    private var radioOn = true

    @Suppress("DEPRECATION") // the only way in for a priv-app: Android's own tiles call the same
    override fun set(c: Connection, on: Boolean): Boolean = try {
        when (c) {
            Connection.MOBILE -> {
                if (on) telephony.clearRadioPowerOffForReason(TelephonyManager.RADIO_POWER_REASON_USER)
                else telephony.requestRadioPowerOffForReason(TelephonyManager.RADIO_POWER_REASON_USER)
                true
            }
            Connection.WIFI -> wifi.setWifiEnabled(on)
            Connection.BLUETOOTH -> bluetooth?.let { if (on) it.enable() else it.disable() } ?: false
            Connection.NFC -> nfc?.let { if (on) it.enable() else it.disable() } ?: false
        }.also { if (!it) Log.w(TAG, "$c ${if (on) "on" else "off"} refused") }
    } catch (e: SecurityException) {
        Log.w(TAG, "$c ${if (on) "on" else "off"} refused", e)
        false
    } catch (e: IllegalStateException) { // Telephony's answer when the phone process is not there
        Log.w(TAG, "$c ${if (on) "on" else "off"} failed", e)
        false
    }

    override fun start(onChange: (ConnectionReadings) -> Unit) {
        listener = onChange
        io.post {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
                addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            }
            // System broadcasts reach a receiver that is not exported.
            context.registerReceiver(receiver, filter, null, io, Context.RECEIVER_NOT_EXPORTED)
            try {
                telephony.registerTelephonyCallback({ io.post(it) }, phone)
            } catch (e: SecurityException) {
                Log.w(TAG, "no service state: ${e.message}")
            }
            publish()
        }
    }

    override fun stop() {
        listener = null
        io.post {
            context.unregisterReceiver(receiver)
            telephony.unregisterTelephonyCallback(phone)
        }
    }

    // On io: every reading is a binder call.
    private fun publish() {
        val mask = mask()
        val next = ConnectionReadings(
            on = buildSet {
                if (Connection.MOBILE in available && radioOn) add(Connection.MOBILE)
                if (Connection.WIFI in available && wifi.wifiState.let { it == WifiManager.WIFI_STATE_ENABLED || it == WifiManager.WIFI_STATE_ENABLING }) add(Connection.WIFI)
                if (bluetoothOn()) add(Connection.BLUETOOTH)
                if (nfc?.adapterState.let { it == NfcAdapter.STATE_ON || it == NfcAdapter.STATE_TURNING_ON }) add(Connection.NFC)
            },
            network = if (radioOn && inService) networkName(mask) { telephony.networkOperatorName.orEmpty() } else "",
            airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
            alarm = alarmSet(mask) { alarms.nextAlarmClock != null },
        )
        main.post { listener?.invoke(next) }
    }

    private fun bluetoothOn(): Boolean = try {
        bluetooth?.state.let { it == BluetoothAdapter.STATE_ON || it == BluetoothAdapter.STATE_TURNING_ON }
    } catch (e: SecurityException) {
        Log.w(TAG, "Bluetooth state refused: BLUETOOTH_CONNECT not granted (tools/install.sh --permissions)")
        false
    }

    private companion object {
        const val TAG = "Bold7/Connections"
    }
}
