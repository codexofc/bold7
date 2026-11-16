import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins { id("com.android.application") }

// Status bar images, out of git (provenance in assets-private/MANIFEST.md), copied into this
// checkout's assets-gen by tools/import-assets.sh. The same lines serve any module with private resources:
// assets-gen/<module>/res, added as a resource folder, the build refusing to go without it.
val privateRes = rootProject.file("assets-gen/sysui/res")

android {
    namespace = "dev.bold7.sysui"
    buildTypes.getByName("release").proguardFiles("proguard-rules.pro")
    sourceSets.getByName("main").res.srcDir(privateRes)
}

tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

// A missing image would throw in SystemUI's process: no APK unless the tests pass, among them
// IndicatorsTest.everyImageNameHasItsDrawable, which looks for each image the code can ask for.
// AGP only creates the debug unit tests; the logic is the same in release.
afterEvaluate {
    tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach { dependsOn("testDebugUnitTest") }
}

// What the tests read besides their classes: the images, and the Q25's SystemUI they check the
// fingerprint and hook targets against. Inputs, or a change to them alone would find the tests
// up to date and ship anyway.
tasks.withType<Test>().configureEach {
    inputs.dir(privateRes)
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
