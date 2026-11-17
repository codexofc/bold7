plugins { id("com.android.application") }

// Static RRO of the framework: resources only, mounted by the KernelSU module
// in /system_ext/overlay (system/module), preloaded by the zygote. No image: OS 7's are put on the
// framework drawables it names by the fabricated overlay of the launcher (ImageOverlays,
// values/dialogs.xml).
android {
    namespace = "dev.bold7.overlay.android"
    // The layouts that replace the framework's keep its own ids (@*android:id/buttonPanel…), never
    // new ones of the overlay: what lint calls an invalid id is the point here.
    lint { disable += "InvalidId" }
}

// No code at all (hasCode="false"): keep out the Kotlin stdlib AGP adds to every module.
// On "implementation" only: lint's own classpath needs Kotlin.
configurations.named("implementation") { exclude(group = "org.jetbrains.kotlin") }
