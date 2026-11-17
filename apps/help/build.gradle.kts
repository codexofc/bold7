plugins { id("com.android.application") }

// Help of BBOS 7.1 (docs/BBOS7-VISUEL.md §39): the 9900's help, decoded at install from the user's
// own file (core/firmware, Yk.kt) into the folder the module mounts, /product/media/bold7/help, shown
// in a WebView that reads that folder only; and Bold7's own section (assets/bold7, ours). No
// permission, no network.
android {
    namespace = "dev.bold7.help"
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The components of OS 7 in views: title bar, search field, list rows and bands, menus.
    implementation(project(":core:ui"))
    testImplementation(kotlin("test-junit"))
}
