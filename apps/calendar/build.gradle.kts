plugins { id("com.android.application") }

// The Calendar's images of the 9900's firmware (theme and calendar app), out of git (provenance in
// assets-private/MANIFEST.md), copied into this checkout's assets-gen by tools/import-assets.sh calendar.
val privateRes = rootProject.file("assets-gen/calendar/res")

// Calendar of BBOS 7.1 (docs/BBOS7-VISUEL.md §29): an ordinary app on Android's calendar
// provider. Google Calendar stays the engine of the reminders; this one shows OS 7's box.
android {
    namespace = "dev.bold7.calendar"
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
    // The showcase's made-up week, shown instead of the provider while bold7_showcase is 1.
    implementation(project(":core:showcase"))
    testImplementation(kotlin("test-junit"))
}
