plugins { id("com.android.library") }

// The theme images core/ui draws (border_menu, border_popup, buttons, check boxes...), out of git
// (provenance in assets-private/MANIFEST.md), copied into this checkout's assets-gen by
// tools/import-assets.sh ui; merged into every APK that uses core/ui.
// Read by name at run time (core/theme): a public build (-Pbold7.public=true)
// carries none of them.
val privateRes = rootProject.file("assets-gen/ui/res")
val public = providers.gradleProperty("bold7.public").orNull == "true"

// BBOS 7 components in views, written once for the launcher and the apps.
android {
    namespace = "dev.bold7.ui"
    if (!public) sourceSets.getByName("main").res.srcDir(privateRes)
}

if (!public) tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

dependencies {
    // The BBOS 7 tokens every component draws with.
    api(project(":core:design"))
    // The images of OS 7 by name: api, so that an app preloads its own through the same store.
    api(project(":core:theme"))
    testImplementation(kotlin("test-junit"))
}
