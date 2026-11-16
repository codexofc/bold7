plugins { id("com.android.application") }

// OS 7's English word substitution and the word lists of AOSP and OS 7 merged, out of git
// (provenance in assets-private/MANIFEST.md, "saisie/"), copied into this checkout's assets-gen
// by tools/import-assets.sh saisie.
val privateAssets = rootProject.file("assets-gen/saisie/assets")

// Bold7's input method: an ordinary app, installed by tools/install.sh,
// never in the module nor a priv-app.
android {
    namespace = "dev.bold7.saisie"
    sourceSets.getByName("main").assets.srcDir(privateAssets)
}

tasks.named("preBuild") {
    val dir = privateAssets
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh saisie" } }
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The Sym list drawn as a popup of OS 7 (border_popup.png, the focus blue, BBAlpha).
    implementation(project(":core:ui"))
    testImplementation(kotlin("test-junit"))
}
