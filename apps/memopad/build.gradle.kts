plugins { id("com.android.application") }

// The MemoPad's images of the 9900's theme (field, short menu), out of git (provenance in
// assets-private/MANIFEST.md), copied into this checkout's assets-gen by tools/import-assets.sh memopad.
val privateRes = rootProject.file("assets-gen/memopad/res")

// MemoPad of BBOS 7.1: an ordinary app, its memos in a SQLite database of
// its own (the framework's), no permission, no network.
android {
    namespace = "dev.bold7.memopad"
    sourceSets.getByName("main").res.srcDir(privateRes)
}

tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The components of OS 7 in views: menus, dialogs, title bar, search field, list rows.
    implementation(project(":core:ui"))
    // The made-up memos shown instead of the real ones while bold7_showcase is 1.
    implementation(project(":core:showcase"))
    testImplementation(kotlin("test-junit"))
}
