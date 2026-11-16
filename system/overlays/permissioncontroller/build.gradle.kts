plugins { id("com.android.application") }

// Static RRO of PermissionController (com.google.android.permissioncontroller, in the
// com.google.android.permission APEX): its permission request dialog, as far as the styles it
// declares overlayable go. Resources only, mounted by
// the KernelSU module in /system_ext/overlay (system/module).
// The button images, out of git, copied into this checkout's assets-gen by tools/import-assets.sh
// dialogues.
val privateRes = rootProject.file("assets-gen/permissioncontroller/res")

android {
    namespace = "dev.bold7.overlay.permissioncontroller"
    sourceSets.getByName("main").res.srcDir(privateRes)
}

tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

// No code at all (hasCode="false"): keep out the Kotlin stdlib AGP adds to every module.
// On "implementation" only: lint's own classpath needs Kotlin.
configurations.named("implementation") { exclude(group = "org.jetbrains.kotlin") }
