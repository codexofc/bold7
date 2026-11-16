package dev.bold7.launcher.options

import dev.bold7.platform.DeviceFacts
import java.util.Locale

// The read-only screens under Device and Security, pure: label and value rows of
// what Android reports. The rows' names follow the guide 7.1's device information (B); which
// values the Q25 has instead of the 9900's (PIN, flash memory...) is D.

/** Decimal units, one decimal from the megabyte up, as Android's storage settings count them. */
fun byteSize(bytes: Long): String {
    val units = listOf("B", "kB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var u = 0
    while (v >= 1000 && u < units.lastIndex) { v /= 1000; u++ }
    return if (u < 2) "${v.toLong()} ${units[u]}" else String.format(Locale.US, "%.1f %s", v, units[u])
}

fun aboutRows(f: DeviceFacts): List<Pair<String, String>> = listOf(
    "Model" to f.model,
    "Manufacturer" to f.manufacturer,
    "Android Version" to f.androidVersion,
    "Security Patch" to f.securityPatch,
    "Software Build" to f.build,
    "Kernel" to f.kernel,
)

fun storageRows(f: DeviceFacts): List<Pair<String, String>> = listOf(
    "Total" to byteSize(f.storageTotal),
    "Free" to byteSize(f.storageFree),
    "Used" to "${if (f.storageTotal > 0) ((f.storageTotal - f.storageFree) * 100 / f.storageTotal).toInt() else 0}%",
)

fun batteryRows(f: DeviceFacts): List<Pair<String, String>> = listOf(
    "Level" to "${f.batteryPercent}%",
    "Status" to when (f.batteryStatus) {
        2 -> "Charging"
        3 -> "Discharging"
        4 -> "Not charging"
        5 -> "Full"
        else -> "Unknown"
    },
    "Health" to when (f.batteryHealth) {
        2 -> "Good"
        3 -> "Overheat"
        4 -> "Dead"
        5 -> "Over voltage"
        6 -> "Failure"
        7 -> "Cold"
        else -> "Unknown"
    },
    "Power Source" to when {
        f.plugged and 1 != 0 -> "AC"
        f.plugged and 2 != 0 -> "USB"
        f.plugged and 4 != 0 -> "Wireless"
        else -> "Not plugged in"
    },
    "Temperature" to String.format(Locale.US, "%.1f C", f.temperatureTenths / 10.0),
    "Voltage" to String.format(Locale.US, "%.2f V", f.voltageMv / 1000.0),
)

fun encryptionLabel(f: DeviceFacts) = if (f.encrypted) "On" else "Off"
