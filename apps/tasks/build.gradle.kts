plugins { id("com.android.application") }

// The Tasks' images of the 9900's theme (status and priority strips, field, short menu), out of git
// (provenance in assets-private/MANIFEST.md), copied into this checkout's assets-gen by
// tools/import-assets.sh tasks.
val privateRes = rootProject.file("assets-gen/tasks/res")

// Tasks of BBOS 7.1: an ordinary app, its tasks in a SQLite database of its
// own (the framework's), no network; its reminders on AlarmManager.
android {
    namespace = "dev.bold7.tasks"
    sourceSets.getByName("main").res.srcDir(privateRes)
}

tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The components of OS 7 in views: menus, dialogs, title bar, list rows, choice fields.
    implementation(project(":core:ui"))
    // The made-up tasks shown instead of the real ones while bold7_showcase is 1.
    implementation(project(":core:showcase"))
    testImplementation(kotlin("test-junit"))
    // Tests only, never in the APK: the database's upgrades run on a real SQLite (MigrationTest).
    // sqlite-jdbc, Apache 2.0.
    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")
}
