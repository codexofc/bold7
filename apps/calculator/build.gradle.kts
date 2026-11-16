plugins { id("com.android.application") }

// The Calculator's images of the 9900's theme (display, keys), out of git (provenance in
// assets-private/MANIFEST.md), copied into this checkout's assets-gen by tools/import-assets.sh
// calculatrice. Read by name at run time: from the folder the module mounts, else from
// these compiled resources, which only the development build carries. A public build
// (-Pbold7.public=true) carries none of them.
val privateRes = rootProject.file("assets-gen/calculator/res")
val public = providers.gradleProperty("bold7.public").orNull == "true"

// Calculator of BBOS 7.1: an ordinary app, no permission, no storage.
android {
    namespace = "dev.bold7.calculator"
    if (!public) sourceSets.getByName("main").res.srcDir(privateRes)
}

if (!public) tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The components of OS 7 in views: the menu, the title bar, the status message; the
    // theme images by name (core/theme, through core/ui).
    implementation(project(":core:ui"))
    testImplementation(kotlin("test-junit"))
}
