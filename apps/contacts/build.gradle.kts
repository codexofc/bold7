plugins { id("com.android.application") }

// The Contacts' images of the 9900's theme (pictures, card boxes, short menu), out of git
// (provenance in assets-private/MANIFEST.md), copied into this checkout's assets-gen by
// tools/import-assets.sh contacts.
val privateRes = rootProject.file("assets-gen/contacts/res")

// Contacts of BBOS 7.1 (docs/BBOS7-VISUEL.md §30): an ordinary app over Android's
// contacts provider, which keeps every contact where it is (nothing copied).
android {
    namespace = "dev.bold7.contacts"
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
    // The made-up people shown instead of the real contacts while bold7_showcase is 1.
    implementation(project(":core:showcase"))
    testImplementation(kotlin("test-junit"))
}
