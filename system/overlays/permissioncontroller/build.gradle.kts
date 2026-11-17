plugins { id("com.android.application") }

// Static RRO of PermissionController (com.google.android.permissioncontroller, in the
// com.google.android.permission APEX): its permission request dialog, as far as the styles it
// declares overlayable go. Resources only, mounted by
// the KernelSU module in /system_ext/overlay (system/module). No image: its buttons name the
// framework drawables the launcher's fabricated overlay fills (ImageOverlays).
android {
    namespace = "dev.bold7.overlay.permissioncontroller"
}

// No code at all (hasCode="false"): keep out the Kotlin stdlib AGP adds to every module.
// On "implementation" only: lint's own classpath needs Kotlin.
configurations.named("implementation") { exclude(group = "org.jetbrains.kotlin") }
