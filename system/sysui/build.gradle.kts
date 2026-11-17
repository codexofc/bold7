import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins { id("com.android.application") }

// The images of the 9900's theme SystemUI draws (banner, indicators, lock screen, volume, the
// shutdown's busy clock), out of git (provenance in assets-private/MANIFEST.md), copied into this
// checkout's assets-gen by tools/import-assets.sh bandeau systeme. Read by name at run time
// (Images, through core/theme): from the folder the module mounts, else from these compiled
// resources, which only the development build carries. A public build (-Pbold7.public=true)
// carries none of them.
val privateRes = rootProject.file("assets-gen/sysui/res")
val public = providers.gradleProperty("bold7.public").orNull == "true"

android {
    namespace = "dev.bold7.sysui"
    buildTypes.getByName("release").proguardFiles("proguard-rules.pro")
    if (!public) sourceSets.getByName("main").res.srcDir(privateRes)
}

if (!public) tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

// The development APK only with its tests passed, among them those that find in assets-gen each
// image the code can ask for: a missing one is drawn empty and counted, never a throw, but
// is a defect. AGP only creates the debug unit tests; the logic is the same in release. A public
// build has no assets-gen to test against.
if (!public) afterEvaluate {
    tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach { dependsOn("testDebugUnitTest") }
}

// What the tests read besides their classes: the images, and the Q25's SystemUI they check the
// fingerprint and hook targets against. Inputs, or a change to them alone would find the tests
// up to date and ship anyway.
tasks.withType<Test>().configureEach {
    if (privateRes.isDirectory) inputs.dir(privateRes)
    inputs.file(rootProject.file("stubs/device/MtkSystemUI.apk"))
    inputs.file(rootProject.file("stubs/systemui.jar"))
}

dependencies {
    // Modern Xposed API (Apache 2.0), provided at run time by Vector, which refuses a module
    // that bundles it.
    compileOnly("io.github.libxposed:api:102.0.0")
    // The framework's hidden task stack listener (StatusBarLayout: the titles of the screens), after
    // android.jar, which keeps every public class: only what it hides comes from here.
    compileOnly(files(rootProject.file("stubs/framework.jar")))
    // Design tokens (design unit, durations, type sizes) shared with the launcher.
    implementation(project(":core:design"))
    // The dialog of OS 7 (DialogLayout, its theme images ui_*, text and focus paints) for the lock
    // screen's password entry, and DialogView for the power key's dialog and the
    // status messages in place of the toasts: one implementation with the apps'. Its
    // views find their images by this APK's ids, through Images.context.
    implementation(project(":core:ui"))
    // The showcase's counters, carrier and alarm (bold7_showcase), for the captures.
    implementation(project(":core:showcase"))
    // The package to icon table and the template of the generated icon, the launcher's: the
    // other apps' indicators in the banners SystemUI draws (AppIndicators).
    implementation(project(":core:icons"))
    testImplementation(kotlin("test-junit"))
}

// The tests compile against the Q25's SystemUI (stubs/systemui.jar, see tools/stubs.sh). Not the main
// code: at run time the module's class loader cannot see SystemUI's classes (Vector loads it first). Appended
// last, because SystemUI bundles an R8-shrunk kotlin stdlib that would shadow the real one; in
// afterEvaluate, or AGP resets the classpath (same reason as core/platform). The device's
// framework.jar goes first, before android.jar as in core/platform, for the hidden framework types
// SystemUI's classes extend (VolumeDialogImpl is a ViewTreeObserver.OnComputeInternalInsetsListener).
afterEvaluate {
    tasks.withType<KotlinCompile>().matching { it.name.contains("UnitTest") }.configureEach {
        val framework = rootProject.file("stubs/framework.jar")
        val stubs = rootProject.file("stubs/systemui.jar")
        libraries.setFrom(framework, libraries.from.toList(), stubs)
        doFirst { listOf(framework, stubs).forEach { check(it.exists()) { "Missing $it: run tools/stubs.sh" } } }
    }
}
