plugins { id("com.android.application") }

// App icons and home images, out of git (provenance in assets-private/MANIFEST.md), copied into
// this checkout's assets-gen by tools/import-assets.sh
// from the table of core/icons (res/raw/bbos7_icons.tsv); the same lines as system/sysui/build.gradle.kts.
// Read by name at run time (core/theme): from the folder the module mounts, else from these
// compiled resources, which only the development build carries. A public build
// (-Pbold7.public=true) carries none of them.
val privateRes = rootProject.file("assets-gen/launcher/res")
val public = providers.gradleProperty("bold7.public").orNull == "true"

android {
    namespace = "dev.bold7.launcher"
    if (!public) sourceSets.getByName("main").res.srcDir(privateRes)
}

if (!public) tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

// No APK unless the tests pass, among them ShippedIconTableTest.everyIconHasItsDrawable: an icon
// the table names and the build lacks would leave its app with its own icon, unnoticed. The
// table and the icons are inputs of the tests, or a change to them alone would find the tests
// up to date and ship anyway.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("core/icons/src/main/res/raw/bbos7_icons.tsv"))
    inputs.files(fileTree(privateRes))
}

afterEvaluate {
    tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach { dependsOn("testDebugUnitTest") }
}

dependencies {
    implementation(project(":core:platform"))
    implementation(project(":core:design"))
    // The components in views the apps share: menus, bars, list headers, trackpad focus.
    implementation(project(":core:ui"))
    // The package to icon table, shared with SystemUI's module.
    implementation(project(":core:icons"))
    // The inbox engine's model and the provider's contract (the banner's counts, the list of
    // notifications). The database and the provider stay in apps/hub's process.
    implementation(project(":core:hub-data"))
    // The made-up data shown while the showcase is on (bold7_showcase): the inbox, the search, the alarm.
    implementation(project(":core:showcase"))
    // @Keep on ImageOverlays, which only app_process calls (system/module overlays.sh). Annotation
    // only: the artifact is already in the APK through Room, and its R8 rule with it. Apache 2.0.
    compileOnly("androidx.annotation:annotation:1.9.1")
    // StateFlow of the quickstep state the home collects (docs/ARCHITECTURE.md §3). Apache 2.0.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
    // TestDispatcher for the home model's io work (docs/ARCHITECTURE.md §8). Apache 2.0.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
