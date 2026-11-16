plugins { id("com.android.application") }

// Static RRO of the BlackBerry keyboard (BBKB, com.blackberry.keyboard, /system/priv-app/BBKB), the
// Q25's input method: its suggestion strip, the only part shown above the physical keyboard,
// dressed as the Classic's prediction bar. Resources only,
// mounted by the KernelSU module in /system_ext/overlay (system/module).
android { namespace = "dev.bold7.overlay.bbkb" }

// No code at all (hasCode="false"): keep out the Kotlin stdlib AGP adds to every module.
// On "implementation" only: lint's own classpath needs Kotlin.
configurations.named("implementation") { exclude(group = "org.jetbrains.kotlin") }
