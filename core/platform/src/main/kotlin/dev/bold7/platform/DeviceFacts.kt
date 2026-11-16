package dev.bold7.platform

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.text.format.DateFormat
import java.util.TimeZone

/**
 * What Options' Device and Date and Time screens show, as plain numbers and text:
 * the battery's codes are BatteryManager's (status 2 charging, 3 discharging, 4 not charging, 5 full;
 * health 2 good, 3 overheat, 4 dead, 5 over voltage, 6 failure, 7 cold; plugged a mask 1 AC, 2 USB, 4 wireless).
 */
data class DeviceFacts(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val securityPatch: String,
    val build: String,
    val kernel: String,
    val storageTotal: Long,
    val storageFree: Long,
    val batteryPercent: Int,
    val batteryStatus: Int,
    val batteryHealth: Int,
    val plugged: Int,
    val temperatureTenths: Int,
    val voltageMv: Int,
    val encrypted: Boolean,
    val timeZoneId: String,
    val timeZoneOffsetMinutes: Int,
    val locale24Hour: Boolean,
)

/** Reads [DeviceFacts]; [SystemDeviceFacts] on the phone, a fake in the tests. IO thread. */
interface DeviceFactsSource {
    fun facts(): DeviceFacts
}

/** Build, StatFs, the sticky battery intent and the policy manager's encryption status: no permission, no binder call that blocks long. */
class SystemDeviceFacts(private val context: Context) : DeviceFactsSource {
    override fun facts(): DeviceFacts {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        fun int(name: String, default: Int = 0) = battery?.getIntExtra(name, default) ?: default
        val scale = int(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val data = StatFs(Environment.getDataDirectory().path)
        val zone = TimeZone.getDefault()
        val encryption = context.getSystemService(DevicePolicyManager::class.java).storageEncryptionStatus
        return DeviceFacts(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.RELEASE,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            build = Build.DISPLAY,
            kernel = System.getProperty("os.version").orEmpty(),
            storageTotal = data.totalBytes,
            storageFree = data.availableBytes,
            batteryPercent = int(BatteryManager.EXTRA_LEVEL) * 100 / scale,
            batteryStatus = int(BatteryManager.EXTRA_STATUS),
            batteryHealth = int(BatteryManager.EXTRA_HEALTH),
            plugged = int(BatteryManager.EXTRA_PLUGGED),
            temperatureTenths = int(BatteryManager.EXTRA_TEMPERATURE),
            voltageMv = int(BatteryManager.EXTRA_VOLTAGE),
            encrypted = encryption == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE || encryption == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER,
            timeZoneId = zone.id,
            timeZoneOffsetMinutes = zone.getOffset(System.currentTimeMillis()) / 60_000,
            locale24Hour = DateFormat.is24HourFormat(context),
        )
    }
}
