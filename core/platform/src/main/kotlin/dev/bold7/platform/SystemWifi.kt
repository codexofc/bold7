package dev.bold7.platform

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.util.Log

/**
 * Android's Wi-Fi as a priv-app of the module may use it (read in the Q25's
 * service-wifi.jar, WifiServiceImpl and WifiPermissionsUtil): no su, no hidden API.
 * - Scan results and saved networks (getScanResults, startScan, getConfiguredNetworks) go through
 *   enforceCanAccessScanResults: without NETWORK_SETTINGS (signature) nor RADIO_SCAN_WITHOUT_LOCATION
 *   (signature|companion), the caller must hold ACCESS_FINE_LOCATION (runtime, granted by
 *   tools/install.sh) and location must be on. Refused, Android answers an empty list: [WifiState.allowed]
 *   says so first.
 * - addNetwork, enableNetwork and removeNetwork (deprecated for apps, kept for this) are allowed to
 *   a system app (isTargetSdkLessThanQOrPrivileged: isSystem). Changing a network another app saved
 *   (Android's Settings) passes WifiConfigManager.canModifyNetwork: OVERRIDE_WIFI_CONFIG
 *   (signature|privileged|knownSigner), granted by the privapp allowlist.
 *
 * The state is read on [io] (binder calls) at each broadcast and handed to the main thread.
 */
@Suppress("DEPRECATION") // the configured-network calls a system app is still allowed
class SystemWifi(private val context: Context, private val io: Handler) : WifiNetworks {
    private val main = Handler(context.mainLooper)
    private val wifi by lazy { context.getSystemService(WifiManager::class.java) }
    private val location by lazy { context.getSystemService(LocationManager::class.java) }

    // Set on the main thread only: a reading posted before stop() is dropped.
    private var listener: ((WifiState) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = publish()
    }

    override fun start(onChange: (WifiState) -> Unit) {
        listener = onChange
        io.post {
            val filter = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(CONFIGURED_NETWORKS_CHANGED)
            }
            // System broadcasts reach a receiver that is not exported.
            context.registerReceiver(receiver, filter, null, io, Context.RECEIVER_NOT_EXPORTED)
            publish()
        }
    }

    override fun stop() {
        listener = null
        io.post { context.unregisterReceiver(receiver) }
    }

    override fun scan(): Boolean = refused("scan") { wifi.startScan() }

    override fun join(ssid: String, security: WifiSecurity, password: CharSequence?): Boolean {
        val type = when (security) {
            WifiSecurity.OPEN -> WifiConfiguration.SECURITY_TYPE_OPEN
            WifiSecurity.OWE -> WifiConfiguration.SECURITY_TYPE_OWE
            WifiSecurity.PERSONAL -> WifiConfiguration.SECURITY_TYPE_PSK
            WifiSecurity.WPA3 -> WifiConfiguration.SECURITY_TYPE_SAE
            WifiSecurity.WEP, WifiSecurity.ENTERPRISE -> return false
        }
        val config = WifiConfiguration().apply {
            SSID = "\"" + ssid + "\""
            setSecurityParams(type)
            // A 64-digit hex key is the raw PSK, any other a passphrase in quotes (WifiConfiguration).
            if (password != null) preSharedKey = if (password.length == 64) password.toString() else "\"" + password + "\""
        }
        // The configuration is never logged: it holds the password.
        return refused("join") {
            val id = wifi.addNetwork(config)
            id != -1 && wifi.enableNetwork(id, true)
        }
    }

    override fun connect(id: Int): Boolean = refused("connect") { wifi.enableNetwork(id, true) }

    override fun forget(id: Int): Boolean = refused("forget") { wifi.removeNetwork(id) }

    // On io: every reading is a binder call.
    private fun publish() {
        val on = wifi.isWifiEnabled
        val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            location.isLocationEnabled
        val max = wifi.maxSignalLevel
        val seen = if (!on || !allowed) emptyList() else wifi.scanResults.mapNotNull { r ->
            val ssid = r.wifiSsid?.toString()?.removeSurrounding("\"").orEmpty()
            if (ssid.isEmpty() || ssid == UNKNOWN) null else WifiSeen(ssid, wifiBars(wifi.calculateSignalLevel(r.level), max), wifiSecurity(r.capabilities.orEmpty()))
        }
        val saved = if (!allowed) emptyList() else wifi.configuredNetworks.orEmpty().map { c ->
            WifiSaved(c.networkId, c.SSID.orEmpty().removeSurrounding("\""), savedSecurity(c))
        }
        val connected = wifi.connectionInfo?.takeIf { it.networkId != -1 }?.ssid?.removeSurrounding("\"")?.takeIf { it != UNKNOWN }
        val next = WifiState(on, allowed, seen, saved, connected)
        main.post { listener?.invoke(next) }
    }

    private fun savedSecurity(c: WifiConfiguration): WifiSecurity = when {
        c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) -> WifiSecurity.PERSONAL
        c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE) -> WifiSecurity.WPA3
        c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE) -> WifiSecurity.OWE
        c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP) || c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X) -> WifiSecurity.ENTERPRISE
        c.wepKeys.any { it != null } -> WifiSecurity.WEP
        else -> WifiSecurity.OPEN
    }

    private inline fun refused(what: String, call: () -> Boolean): Boolean = try {
        call().also { if (!it) Log.w(TAG, "$what refused") }
    } catch (e: SecurityException) {
        Log.w(TAG, "$what refused: ${e.message}")
        false
    }

    private companion object {
        const val TAG = "Bold7/Wifi"
        const val UNKNOWN = "<unknown ssid>"
        // WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION, a system API: sent when a network is saved or forgotten.
        const val CONFIGURED_NETWORKS_CHANGED = "android.net.wifi.CONFIGURED_NETWORKS_CHANGE"
    }
}
