plugins { id("com.android.application") }

// Static framework RRO that makes the Bold7 launcher the recents provider.
// Resources only, mounted by the KernelSU module in /system_ext/overlay (system/module).
android { namespace = "dev.bold7.overlay.recents" }

// No code at all (hasCode="false"): keep out the Kotlin stdlib AGP adds to every module.
// On "implementation" only: lint's own classpath needs Kotlin.
configurations.named("implementation") { exclude(group = "org.jetbrains.kotlin") }
