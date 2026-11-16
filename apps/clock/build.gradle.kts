plugins { id("com.android.application") }

// The Clock's images of the 9900's firmware (faces, hands, buttons, the theme's), out of git
// (provenance in assets-private/MANIFEST.md), copied into this checkout's assets-gen by
// tools/import-assets.sh clock.
val privateRes = rootProject.file("assets-gen/clock/res")

// Clock of BBOS 7.1: an ordinary app, its alarm rung by AlarmManager's
// alarm clock, a foreground service and a full-screen notification, armed again at boot.
android {
    namespace = "dev.bold7.clock"
    sourceSets.getByName("main").res.srcDir(privateRes)
}

tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The components of OS 7 in views: menus, dialogs, title bar, check boxes, choice fields.
    implementation(project(":core:ui"))
    // The made-up alarm shown instead of the real one while bold7_showcase is 1.
    implementation(project(":core:showcase"))
    testImplementation(kotlin("test-junit"))
}
