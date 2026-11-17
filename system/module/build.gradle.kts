import com.android.tools.profgen.ArtProfile
import com.android.tools.profgen.ArtProfileSerializer
import com.android.tools.profgen.MetadataVersion
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins { base }

// Generated assets (out of git) that tools/import-assets.sh writes in this checkout's assets-gen,
// from the sources of the shared assets-private, read-only: module/system/fonts holds the BBAlpha
// TTFs, device/fonts.xml is the phone's stock fonts.xml.
val assets = rootProject.file("assets-gen")

// The module's fonts: the BBAlpha TTFs of assets-gen copied with their hhea line metrics set to their
// win metrics (tools/ttf_metrics.py: BBAlphaSans.ttf alone has a shorter line than its siblings, its
// accented capitals rising above its ascent). The script fails the build if a copy still differs.
val moduleFonts by tasks.registering(Exec::class) {
    val script = rootProject.file("tools/ttf_metrics.py")
    val src = assets.resolve("module/system/fonts")
    val out = layout.buildDirectory.dir("fonts/system/fonts")
    inputs.file(script)
    inputs.dir(src)
    outputs.dir(out)
    doFirst { out.get().asFile.deleteRecursively() }
    commandLine("/usr/bin/python3", "-I", script, src, out.get().asFile)
}

// The phone's fonts.xml with BBAlpha as sans-serif, condensed, serif and monospace (tools/fonts_xml.py), mounted over
// /system/etc/fonts.xml. The script refuses a missing font or an output that does not parse.
// The 9900's fallback fonts (tools/fonts_xml.py), copied as they are.
val fallbackFonts = assets.resolve("module/system/fonts-os7")

val fontsXml by tasks.registering(Exec::class) {
    val script = rootProject.file("tools/fonts_xml.py")
    val stock = assets.resolve("device/fonts.xml")
    val fonts = layout.buildDirectory.dir("fonts/system/fonts").get().asFile
    val out = layout.buildDirectory.file("fonts-xml/fonts.xml")
    dependsOn(moduleFonts)
    inputs.files(script, stock)
    inputs.dir(fonts)
    inputs.dir(fallbackFonts)
    outputs.file(out)
    // macOS's own python, the only one on the maintainer's Mac whose pyexpat loads (Homebrew's
    // 3.13 to 3.15 fail on the system libexpat). Any python3 >= 3.9 with expat would do.
    commandLine("/usr/bin/python3", "-I", script, stock, fonts, out.get().asFile, fallbackFonts)
}

// Deliberately simple: APKs read from their output paths; the artifacts API when more apps join.
fun apk(dir: String) = rootProject.file("$dir/build/outputs/apk/release/${dir.substringAfterLast('/')}-release.apk")
fun assembleOf(dir: String) = ":${dir.replace('/', ':')}:assembleRelease"

// Every priv-app the module embeds, folder name to project: the one list both the zip
// (layer/system/priv-app/<name>/<name>.apk) and privappCheck read.
val privApps = mapOf("Bold7Launcher" to "apps/launcher")
// Every RRO, file name to project, mounted in /system_ext/overlay.
val overlays = mapOf(
    "Bold7Recents" to "system/overlays/recents",
    "Bold7Android" to "system/overlays/android",
    "Bold7SystemUI" to "system/overlays/systemui",
    "Bold7BBKB" to "system/overlays/bbkb",
    "Bold7PermissionController" to "system/overlays/permissioncontroller",
    "Bold7Settings" to "system/overlays/settings",
)
// The app-name RROs: one APK per id of system/overlays/names/names.tsv (a product flavor each),
// Bold7Name_<id>.apk in the same folder.
val nameIds = rootProject.file("system/overlays/names/names.tsv").readLines()
    .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.distinct()
val whitelist = file("src/layer/system/etc/permissions/privapp-permissions-bold7.xml")

// ro.control_privapp_permissions=enforce on the Q25: a priv-app requesting a privileged permission
// missing from the whitelist stops the boot. tools/privapp_check.py fails the build unless every
// permission each embedded priv-app requests has its line in the whitelist, and nothing more, and
// each has its hidden API exemption.
val sdkDir by lazy {
    java.util.Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
    }.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: error("sdk.dir or ANDROID_HOME needed")
}

// The highest of the SDK's versioned folders by version, not by name (36.1.0 over 9.0.0). Up to 4
// numbers below 10000, "-rc1" and the like count as 0.
fun version(name: String) = (name.split('.', '-').map { it.toIntOrNull() ?: 0 } + List(4) { 0 })
    .take(4).fold(0L) { acc, n -> acc * 10000 + n }

val privappCheck by tasks.registering(Exec::class) {
    dependsOn(privApps.values.map(::assembleOf))
    // aapt2's dump format has not changed in years: the highest build-tools.
    val aapt2 = File(sdkDir, "build-tools").listFiles()!!.filter { it.resolve("aapt2").exists() }
        .maxBy { version(it.name) }.resolve("aapt2")
    val apks = privApps.values.map(::apk)
    inputs.files(apks, whitelist, rootProject.file("tools/privapp_check.py"))
    // Same python as fontsXml: the whitelist is parsed with pyexpat.
    commandLine(listOf("/usr/bin/python3", "-I", rootProject.file("tools/privapp_check.py"), aapt2, whitelist) + apks)
}

// The launcher's baseline profile as ART on Android 14 reads it for a system app: <apk>.prof beside
// the APK (PrimaryDexUtils.getExternalProfiles; artd copyAndRewriteProfile, docs/PERF.md), in ART's
// own format 015 only (ProfileCompilationInfo). AGP packs the profile for ProfileInstaller instead,
// in format 010 plus a metadata file (assets/dexopt): profgen, AGP's own, joins and rewrites them.
// Read from the signed APK itself, so its dex checksums are those ART checks.
abstract class SystemAppProfile : DefaultTask() {
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
        check(profile.profileData.values.sumOf { it.methods.size } > 0) { "empty profile in $apkFile" }
        output.get().asFile.outputStream().use { profile.save(it, ArtProfileSerializer.V0_1_5_S) }
    }
}

// Deliberately simple: the launcher only, the one priv-app on the hot path; the others when they have rules.
val launcherProfile by tasks.registering(SystemAppProfile::class) {
    dependsOn(assembleOf("apps/launcher"))
    apk = apk("apps/launcher")
    output = layout.buildDirectory.file("profile/Bold7Launcher.apk.prof")
}

// BBOS 7 sounds laid in /product/media/audio (docs/BBOS7-VISUEL.md, section 20): sounds.tsv names each
// one, path on the phone, firmware source (which tools/import-assets.sh copies to
// assets-gen/sounds) and title. The firmware's tones are AAC .m4a, which Android reads as is:
// they are copied, not re-encoded, so nothing is lost. The other sources are converted by ffmpeg:
// to FLAC (the Sonar WAV, lossless, title kept for the pickers) or, for the interface sounds whose
// .ogg names Android fixes, to Ogg Opus. The title goes in as a tag, which MediaStore shows; artist, album and genre are emptied (the firmware uses them as labels: Notifier, Remix, Audio Alert).
// ffmpeg (Homebrew) is the only tool on this Mac that writes these tags. It runs at build time
// only, nothing of it goes into the module.
abstract class BbosSounds : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:InputFile abstract val table: RegularFileProperty
    @get:InputDirectory abstract val sources: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun convert() {
        val out = output.get().asFile.apply { deleteRecursively() }
        table.get().asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val (dest, source, title) = line.split('\t')
            val input = sources.get().file(source).asFile
            check(input.isFile) { "$input missing: run tools/import-assets.sh" }
            val target = out.resolve("product/media/audio/$dest").apply { parentFile.mkdirs() }
            val codec = when {
                dest.endsWith(".ogg") -> listOf("-c:a", "libopus", "-b:a", "192k")
                dest.endsWith(".m4a") -> listOf("-c:a", "copy")
                else -> emptyList()
            }
            exec.exec {
                commandLine(listOf("ffmpeg", "-v", "error", "-nostdin", "-i", input.path, "-map", "0:a", "-map_metadata", "0",
                    "-metadata", "title=$title", "-metadata", "artist=", "-metadata", "album=", "-metadata", "genre=", "-fflags", "+bitexact", "-flags:a", "+bitexact") + codec + target.path)
            }
        }
    }
}

val sounds by tasks.registering(BbosSounds::class) {
    table = file("sounds.tsv")
    sources = assets.resolve("sounds")
    output = layout.buildDirectory.dir("sounds")
}

// The 9900's boot screen as /product/media/bootanimation.zip (docs/BBOS7-VISUEL.md, section 11),
// drawn by tools/bootanim.py: logo cut by `tools/import-assets.sh demarrage` into assets-gen/bootanim,
// bar measured in the script. The script checks the zip it writes (stored entries, desc.txt, 720x720
// frames) and fails otherwise: a malformed archive never reaches the module. Same python as fontsXml;
// ImageMagick at build time.
val bootAnimation by tasks.registering(Exec::class) {
    val script = rootProject.file("tools/bootanim.py")
    val logo = assets.resolve("bootanim/logo.png")
    val out = layout.buildDirectory.file("bootanim/product/media/bootanimation.zip")
    inputs.files(script, logo)
    outputs.file(out)
    doFirst { out.get().asFile.parentFile.mkdirs() }
    commandLine("/usr/bin/python3", "-I", script, "build", logo, out.get().asFile)
}

// bold7-trackpad, the trackpad's program (trackpad/bold7_trackpad.c): C,
// no library but the NDK's libc, linked statically for arm64 at Android 14's API level, by the
// NDK pinned here. Its pure model (trackpad/model.c) is tested first on the Mac, by the
// Mac's own clang: the binary does not build without its tests.
val trackpadNdk = "29.0.14206865"
val trackpadSources = fileTree("trackpad") { include("*.c", "*.h") }
val trackpadTestBuild by tasks.registering(Exec::class) {
    val out = layout.buildDirectory.file("trackpad-test/test_model")
    inputs.files(trackpadSources)
    outputs.file(out)
    doFirst { out.get().asFile.parentFile.mkdirs() }
    commandLine("/usr/bin/clang", "-std=c11", "-Wall", "-Wextra", "-Werror", "-o", out.get().asFile,
        file("trackpad/model.c"), file("trackpad/test_model.c"))
}
val trackpadTest by tasks.registering(Exec::class) {
    dependsOn(trackpadTestBuild)
    val binary = layout.buildDirectory.file("trackpad-test/test_model")
    inputs.file(binary)
    outputs.upToDateWhen { false }
    commandLine(binary.get().asFile)
}
// bold7-led, the LED's program (led/bold7_led.c): built and tested the same way.
val ledSources = fileTree("led") { include("*.c", "*.h") }
val ledTestBuild by tasks.registering(Exec::class) {
    val out = layout.buildDirectory.file("led-test/test_model")
    inputs.files(ledSources)
    outputs.file(out)
    doFirst { out.get().asFile.parentFile.mkdirs() }
    commandLine("/usr/bin/clang", "-std=c11", "-Wall", "-Wextra", "-Werror", "-o", out.get().asFile,
        file("led/model.c"), file("led/test_model.c"))
}
val ledTest by tasks.registering(Exec::class) {
    dependsOn(ledTestBuild)
    val binary = layout.buildDirectory.file("led-test/test_model")
    inputs.file(binary)
    outputs.upToDateWhen { false }
    commandLine(binary.get().asFile)
}
// `./gradlew test` runs them with the JVM tests of the other modules.
tasks.register("test") { dependsOn(trackpadTest, ledTest) }

val trackpadBinary by tasks.registering(Exec::class) {
    dependsOn(trackpadTest)
    val out = layout.buildDirectory.file("trackpad/bin/bold7-trackpad")
    inputs.files(trackpadSources)
    outputs.file(out)
    doFirst { out.get().asFile.parentFile.mkdirs() }
    // The NDK pinned, so that the binary changes only with its sources; its one prebuilt host
    // folder (darwin-x86_64 on this Mac) found rather than named.
    val ndk = File(sdkDir, "ndk/$trackpadNdk")
    val host = ndk.resolve("toolchains/llvm/prebuilt").listFiles()?.singleOrNull { it.isDirectory }
        ?: error("NDK $trackpadNdk missing in $sdkDir/ndk (or not one prebuilt host): install it with the SDK manager")
    val clang = host.resolve("bin/aarch64-linux-android34-clang")
    commandLine(clang, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-static", "-s", "-o", out.get().asFile,
        file("trackpad/bold7_trackpad.c"), file("trackpad/model.c"))
}

val ledBinary by tasks.registering(Exec::class) {
    dependsOn(ledTest)
    val out = layout.buildDirectory.file("led/bin/bold7-led")
    inputs.files(ledSources)
    outputs.file(out)
    doFirst { out.get().asFile.parentFile.mkdirs() }
    val ndk = File(sdkDir, "ndk/$trackpadNdk")
    val host = ndk.resolve("toolchains/llvm/prebuilt").listFiles()?.singleOrNull { it.isDirectory }
        ?: error("NDK $trackpadNdk missing in $sdkDir/ndk (or not one prebuilt host): install it with the SDK manager")
    commandLine(host.resolve("bin/aarch64-linux-android34-clang"), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-static", "-s",
        "-o", out.get().asFile, file("led/bold7_led.c"), file("led/model.c"))
}

// The ordinary apps the module installs by pm install from customize.sh (setup.sh install), package to
// project, in the order setup.sh installs them; dev.bold7.sysui is the Vector module of SystemUI.
val apps = mapOf(
    "dev.bold7.hub" to "apps/hub",
    "dev.bold7.phone" to "apps/phone",
    "dev.bold7.messages" to "apps/messages",
    "dev.bold7.saisie" to "apps/saisie",
    "dev.bold7.clock" to "apps/clock",
    "dev.bold7.calendar" to "apps/calendar",
    "dev.bold7.contacts" to "apps/contacts",
    "dev.bold7.calculator" to "apps/calculator",
    "dev.bold7.flashlight" to "apps/flashlight",
    "dev.bold7.memopad" to "apps/memopad",
    "dev.bold7.tasks" to "apps/tasks",
    "dev.bold7.pictures" to "apps/pictures",
    "dev.bold7.help" to "apps/help",
    "dev.bold7.sysui" to "system/sysui",
)

// Vector v2.2 (build 3080, JingMatrix's fork of LSPosed, GPL-3.0), the version tested on the Q25,
// embedded so that customize.sh installs it when it is missing. The zip lives out of
// git, in vendor/ of the main checkout (a link in a worktree); refused unless its sha256 matches.
val vectorZip = rootProject.file("vendor/vector/Vector-v2.2-3080-Release.zip")
val vectorCheck by tasks.registering {
    val zip = vectorZip
    val sha256 = "9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc"
    inputs.file(zip)
    doLast {
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(zip.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == sha256) { "$zip: sha256 $actual, not $sha256" }
    }
}

// The KernelSU module zip. Files go to layer/<partition>/<dir>, which
// post-fs-data.sh lays over /<partition>/<dir>: one overlay mount per folder. Never under system/,
// which KernelSU would hand to a metamodule. /system has no overlay folder (PackagePartitions):
// RROs go to /system_ext/overlay, whose static overlays win.
val publicBuild = providers.gradleProperty("bold7.public").orNull == "true"

// The extractor (core/firmware) as one dex jar, with the two tables it reads, for the public zip:
// customize.sh runs it by app_process on the user's own file of the 9900 (src/extract.sh).
val extractorDex = rootProject.file("core/firmware/build/dex/bold7-firmware.jar")

val moduleZip by tasks.registering(Zip::class) {
    archiveFileName = "bold7-module.zip"
    destinationDirectory = layout.buildDirectory.dir("dist")
    from("src")
    // The card's banner in KernelSU Next's module list (banner= of module.prop, read from the
    // module's folder by its manager, Module.kt of v3.4.2): our logo, docs/logo/README.md.
    from(rootProject.file("docs/logo/banner.png"))
    from(trackpadBinary) { into("bin") }
    from(ledBinary) { into("bin") }
    if (publicBuild) {
        // Nothing of BlackBerry: fonts, fonts.xml, sounds, boot animation and images are made on the
        // phone at install, from the user's file (extract.sh).
        dependsOn(":core:firmware:extractorDex")
        from(extractorDex) { into("firmware") }
        from(rootProject.file("core/icons/src/main/res/raw/bbos7_icons.tsv")) { into("firmware") }
        from("sounds.tsv") { into("firmware") }
        // Typing's AOSP French list (Apache 2.0, in git): the extraction lays OS 7's part over it.
        from(rootProject.file("apps/saisie/src/main/assets/dictionary/fr.tsv")) { into("firmware/dictionary") }
    } else {
        from(assets.resolve("module")) { into("layer"); exclude("system/fonts/**", "system/fonts-os7/**") }
        from(fallbackFonts) { into("layer/system/fonts") }
        from(moduleFonts) { into("layer/system/fonts") }
        from(fontsXml) { into("layer/system/etc") }
        from(sounds) { into("layer") }
        from(layout.buildDirectory.dir("bootanim")) { into("layer") }
        // The images the apps read by name (core/theme), laid out as assets-gen: post-fs-data.sh
        // lays layer/product/media over /product/media, so they show at /product/media/bold7 once the
        // module is installed and the phone restarted, with the wallpapers the Setup Wizard offers
        // and the decoded help the launcher's Help shows. The public zip gets them from the extraction.
        from(assets) { include("*/res/**", "wallpapers/**", "help/**"); into("layer/product/media/bold7") }
        dependsOn(bootAnimation)
    }
    dependsOn(privappCheck, overlays.values.map(::assembleOf))
    privApps.forEach { (name, dir) -> from(apk(dir)) { into("layer/system/priv-app/$name"); rename { "$name.apk" } } }
    from(launcherProfile) { into("layer/system/priv-app/Bold7Launcher") }
    dependsOn(apps.values.map(::assembleOf))
    apps.forEach { (pkg, dir) -> from(apk(dir)) { into("apps"); rename { "$pkg.apk" } } }
    from(vectorZip) { into("vector"); rename { "vector.zip" } }
    from(rootProject.file("NOTICE")) { into("vector") }
    dependsOn(vectorCheck)
    overlays.forEach { (name, dir) -> from(apk(dir)) { into("layer/system_ext/overlay"); rename { "$name.apk" } } }
    dependsOn(":system:overlays:names:assembleRelease")
    nameIds.forEach { id ->
        from(rootProject.file("system/overlays/names/build/outputs/apk/$id/release/names-$id-release.apk")) {
            into("layer/system_ext/overlay"); rename { "Bold7Name_$id.apk" }
        }
    }
}

tasks.assemble { dependsOn(moduleZip) }

// The public zip (-Pbold7.public=true, tools/release.sh) must carry nothing of BlackBerry: fonts,
// sounds, boot animation and images are extracted on the phone. What other parts still carry is
// refused here before anything runs; remove a line once its part is done, the list empty, the
// public zip builds, then the root build's PublicZipCheck checks every file in it.
val publicZipBlockers = emptyList<String>()
if (publicBuild) gradle.taskGraph.whenReady {
    if (hasTask(moduleZip.get()) && publicZipBlockers.isNotEmpty()) throw GradleException(
        "No public module zip yet: it would still carry BlackBerry files:\n" +
            publicZipBlockers.joinToString("\n") { "  - $it" })
}
