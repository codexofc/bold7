plugins { id("com.android.application") }

// The Pictures' images of the 9900's theme (no-picture icon and thumbnail, short menu), out of git
// (provenance in assets-private/MANIFEST.md), copied into this checkout's assets-gen by
// tools/import-assets.sh pictures.
// Read by name at run time: from the folder the module mounts, else from these compiled
// resources, which only the development build carries. A public build (-Pbold7.public=true)
// carries none of them.
val privateRes = rootProject.file("assets-gen/pictures/res")
val public = providers.gradleProperty("bold7.public").orNull == "true"

// Pictures of BBOS 7.1: an ordinary app over MediaStore's pictures, no
// network, nothing stored of its own but its options.
android {
    namespace = "dev.bold7.pictures"
    if (!public) sourceSets.getByName("main").res.srcDir(privateRes)
}

if (!public) tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The components of OS 7 in views: menus, dialogs, title bar.
    implementation(project(":core:ui"))
    // The made-up pictures shown instead of MediaStore's while bold7_showcase is 1.
    implementation(project(":core:showcase"))
    testImplementation(kotlin("test-junit"))
}
