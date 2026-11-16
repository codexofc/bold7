plugins { id("com.android.application") }

// Static RRO of MtkSystemUI for the status bar: resources only, mounted by
// the KernelSU module in /system_ext/overlay (system/module), loaded by SystemUI's own process
//. What a resource cannot do is in system/sysui.
android { namespace = "dev.bold7.overlay.systemui" }

// No code at all (hasCode="false"): keep out the Kotlin stdlib AGP adds to every module.
// On "implementation" only: lint's own classpath needs Kotlin.
configurations.named("implementation") { exclude(group = "org.jetbrains.kotlin") }
