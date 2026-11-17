plugins { id("com.android.application") }

// The Clock's images of the 9900's firmware (faces, hands, buttons, the theme's), out of git
// (provenance in assets-private/MANIFEST.md), copied into this checkout's assets-gen by
// tools/import-assets.sh clock.
// Read by name at run time: from the folder the module mounts, else from these compiled
// resources, which only the development build carries. A public build (-Pbold7.public=true)
// carries none of them.
val privateRes = rootProject.file("assets-gen/clock/res")
val public = providers.gradleProperty("bold7.public").orNull == "true"

// Clock of BBOS 7.1: an ordinary app, its alarm rung by AlarmManager's
// alarm clock, a foreground service and a full-screen notification, armed again at boot.
android {
    namespace = "dev.bold7.clock"
    if (!public) sourceSets.getByName("main").res.srcDir(privateRes)
}

if (!public) tasks.named("preBuild") {
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
