plugins { id("com.android.application") }

// The theme images the framework's dialogs, menus and buttons are drawn with (border_popup,
// border_menu, border_button), out of git (provenance in assets-private/MANIFEST.md), copied into
// this checkout's assets-gen by tools/import-assets.sh dialogues.
val privateRes = rootProject.file("assets-gen/android/res")

// Static RRO of the framework: resources only, mounted by the KernelSU module
// in /system_ext/overlay (system/module), preloaded by the zygote.
android {
    namespace = "dev.bold7.overlay.android"
    sourceSets.getByName("main").res.srcDir(privateRes)
    // The layouts that replace the framework's keep its own ids (@*android:id/buttonPanel…), never
    // new ones of the overlay: what lint calls an invalid id is the point here.
    lint { disable += "InvalidId" }
}

tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

// No code at all (hasCode="false"): keep out the Kotlin stdlib AGP adds to every module.
// On "implementation" only: lint's own classpath needs Kotlin.
configurations.named("implementation") { exclude(group = "org.jetbrains.kotlin") }
