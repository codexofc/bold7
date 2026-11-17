import com.android.tools.profgen.ArtProfile
import com.android.tools.profgen.ArtProfileSerializer
import com.android.tools.profgen.MetadataVersion
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins { id("com.android.application") }

// BB10 glyphs of the demo of core/ui (DemoActivity), out of git (provenance in
// assets-private/MANIFEST.md), copied into this checkout's assets-gen by tools/import-assets.sh; the same lines as apps/launcher/build.gradle.kts.
// Read by name at run time: from the folder the module mounts, else from these compiled
// resources, which only the development build carries. A public build (-Pbold7.public=true)
// carries none of them.
val privateRes = rootProject.file("assets-gen/essai/res")
val public = providers.gradleProperty("bold7.public").orNull == "true"

// The test app, never part of the module: what a change shows on the phone may need an ordinary
// app to trigger it. Installed by hand during a test session, removed after.
android {
    namespace = "dev.bold7.essai"
    if (!public) sourceSets.getByName("main").res.srcDir(privateRes)
}

if (!public) tasks.named("preBuild") {
    val dir = privateRes
    doFirst { check(dir.isDirectory) { "Missing $dir: run tools/import-assets.sh" } }
}

dependencies {
    // The demo of core/ui's components in views.
    implementation(project(":core:ui"))
    // The widgets ordinary apps embed rather than take from the framework (WidgetsActivity), all
    // Apache 2.0; this app is never part of the module.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}

// The demo's baseline profile (src/main/baseline-prof.txt) as ART on Android 14 reads it for an
// app installed in /data: a dex metadata file beside the APK, `adb install-multiple essai.apk
// essai.dm`, whose primary.prof is in ART's own format 015 (docs/PERF.md §1). AGP packs it for
// ProfileInstaller instead (format 010 and its metadata, assets/dexopt): profgen, AGP's own,
// joins and rewrites them, read from the signed APK so that the dex checksums are those ART
// checks. The same conversion as system/module's launcherProfile, there written as <apk>.prof.
abstract class DexMetadata : DefaultTask() {
    @get:InputFile abstract val apk: RegularFileProperty
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction fun write() {
        val apkFile = apk.get().asFile
        val profile = ZipFile(apkFile).use { zip ->
            fun read(name: String): ArtProfile {
                val entry = zip.getEntry("assets/dexopt/$name") ?: error("$apkFile has no assets/dexopt/$name: no baseline-prof.txt?")
                return zip.getInputStream(entry).use { ArtProfile(it) } ?: error("$name of $apkFile: unknown profile version")
            }
            read("baseline.prof").addMetadata(read("baseline.profm"), MetadataVersion.V_002)
        }
        val methods = profile.profileData.values.sumOf { it.methods.size }
        check(methods > 0) { "empty profile in $apkFile" }
        // save() closes its stream: written whole first, then zipped.
        val bytes = ByteArrayOutputStream().also { profile.save(it, ArtProfileSerializer.V0_1_5_S) }.toByteArray()
        ZipOutputStream(output.get().asFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("primary.prof"))
            zip.write(bytes)
            zip.closeEntry()
        }
        logger.lifecycle("${output.get().asFile.name}: $methods methods")
    }
}

tasks.register<DexMetadata>("dexMetadata") {
    dependsOn("assembleRelease")
    apk = layout.buildDirectory.file("outputs/apk/release/essai-release.apk")
    output = layout.buildDirectory.file("outputs/dm/essai-release.dm")
}
