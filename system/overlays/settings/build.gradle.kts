plugins { id("com.android.application") }

// Static RRO of MtkSettings (com.android.settings, /system_ext/priv-app/MtkSettings), the Q25's
// Settings app: OS 7 title bar colour, title text and square corners.
// Values only, no layout. Mounted by the KernelSU module in
// /system_ext/overlay (system/module).
android { namespace = "dev.bold7.overlay.settings" }

// No code at all (hasCode="false"): keep out the Kotlin stdlib AGP adds to every module.
// On "implementation" only: lint's own classpath needs Kotlin.
configurations.named("implementation") { exclude(group = "org.jetbrains.kotlin") }
