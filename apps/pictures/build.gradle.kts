plugins { id("com.android.application") }

// The Pictures' images of the 9900's theme (no-picture icon and thumbnail, short menu), out of git
// (provenance in assets-private/MANIFEST.md), copied into this checkout's assets-gen by
// tools/import-assets.sh pictures.
val privateRes = rootProject.file("assets-gen/pictures/res")

// Pictures of BBOS 7.1: an ordinary app over MediaStore's pictures, no
// network, nothing stored of its own but its options.
android {
    namespace = "dev.bold7.pictures"
    sourceSets.getByName("main").res.srcDir(privateRes)
}

tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The components of OS 7 in views: menus, dialogs, title bar.
    implementation(project(":core:ui"))
    testImplementation(kotlin("test-junit"))
}
