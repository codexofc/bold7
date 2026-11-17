plugins { id("com.android.application") }

// The call screen's and the Phone screen's images of the 9900's theme, out of git (provenance in
// assets-private/MANIFEST.md), copied into this checkout's assets-gen by tools/import-assets.sh phone.
// Read by name at run time: from the folder the module mounts, else from these compiled
// resources, which only the development build carries. A public build (-Pbold7.public=true)
// carries none of them.
val privateRes = rootProject.file("assets-gen/phone/res")
val public = providers.gradleProperty("bold7.public").orNull == "true"

// Phone of BBOS 7.1 (docs/BBOS7-VISUEL.md §26): an ordinary app, the DIALER
// role's holder once tools/dialer.sh poser gives it, whose InCallService shows every call.
android {
    namespace = "dev.bold7.phone"
    if (!public) sourceSets.getByName("main").res.srcDir(privateRes)
}

if (!public) tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

dependencies {
    // StrictMode first in the Application (CONTRIBUTING.md).
    implementation(project(":core:platform"))
    // The components of OS 7 in views: menus, search field, status message.
    implementation(project(":core:ui"))
    // The made-up calls and contacts shown instead of the real ones while bold7_showcase is 1.
    implementation(project(":core:showcase"))
    // The calls' StateFlow between the InCallService and the screens, as Messages'. Apache 2.0.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
}
