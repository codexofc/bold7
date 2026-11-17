plugins { id("com.android.application") }

// OS 7's part of Typing's lists (os7/: its English word substitution and the words only its
// firmware has), out of git (provenance in assets-private/MANIFEST.md, "saisie/"), copied into
// this checkout's assets-gen by tools/import-assets.sh saisie. Read at run time from the folder
// the module mounts, else from these assets, which only the development build carries. A public
// build (-Pbold7.public=true) carries none of them: AOSP's word lists and our French substitution
// (src/main/assets) are its only ones until OS 7's part is extracted.
val privateAssets = rootProject.file("assets-gen/saisie/assets")
val public = providers.gradleProperty("bold7.public").orNull == "true"

// Bold7's input method: an ordinary app, installed by tools/install.sh,
// never in the module nor a priv-app.
android {
    namespace = "dev.bold7.saisie"
    if (!public) sourceSets.getByName("main").assets.srcDir(privateAssets)
}

if (!public) tasks.named("preBuild") {
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
