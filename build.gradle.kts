import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.inject.Inject

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
    // Room's annotation processor and schema export, for core/hub-data (Apache 2.0).
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("androidx.room") version "2.8.5" apply false
}

// One device, one SDK level, one signing key for every APK.
// The same key signs debug and release so a priv-app updates with `adb install -r`.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
// keys/bold7.jks with bold7.keystore.password (and bold7.keystore.alias, "bold7" by default). The
// project's former names, keys/cascade.jks with cascade.keystore.password and the alias "cascade",
// are still read, with a warning, so that a machine set up before the rename keeps building.
val legacyKey = !rootProject.file("keys/bold7.jks").exists() && rootProject.file("keys/cascade.jks").exists()
val keystore = rootProject.file(if (legacyKey) "keys/cascade.jks" else "keys/bold7.jks")
val keystorePassword: String? = local.getProperty(if (legacyKey) "cascade.keystore.password" else "bold7.keystore.password")
val keystoreAlias: String = if (legacyKey) "cascade" else local.getProperty("bold7.keystore.alias") ?: "bold7"
if (legacyKey) logger.warn("Signing with keys/cascade.jks (cascade.keystore.password): copy it to keys/bold7.jks and set bold7.keystore.password and bold7.keystore.alias=cascade in local.properties.")

// Public build (-Pbold7.public=true): the modules that read their images by name at run time
// (core/theme) leave assets-gen out of their resources, and every release APK is checked by
// PublicApkCheck before assembleRelease ends. The development build is unchanged.
val publicBuild = providers.gradleProperty("bold7.public").orNull == "true"

/**
 * Fails if an APK, or the module's zip, holds a file of BlackBerry: one whose sha256 is in
 * assets-private's list of the firmware (bbos7-9900.sha256), or one of this checkout's assets-gen,
 * by its bytes or, for an image, by its pixels (aapt2 re-encodes PNGs and strips the border of
 * 9-patches, so a copy never keeps its bytes). The archives inside (the zip's APKs, Vector's zip,
 * the extractor's jar) are read too. Lists every match in its report and in the failure.
 */
/**
 * The icon Android draws for an app of Bold7 outside Bold7 (Settings > Apps, permissions, notices):
 * in the development build, the app's OS 7 icon of the firmware, copied from this checkout's
 * assets-gen (tools/import-assets.sh icones) as the app's mipmap/app_icon, which core/brand's
 * manifest names. The public build has none of it and keeps core/brand's app_icon, Android's own.
 * The Bold7 logo is the launcher's (Options, Setup) and SystemUI's alone.
 */
abstract class AppIcon : DefaultTask() {
    @get:InputFile abstract val source: RegularFileProperty
    @get:OutputDirectory abstract val res: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = res.get().asFile.resolve("mipmap-nodpi").apply { mkdirs() }
        source.get().asFile.copyTo(dir.resolve("app_icon.png"), overwrite = true)
    }
}

/** Each app's OS 7 icon in assets-gen/launcher (core/icons' bbos7_icons.tsv names the same ones; opt_typing is Options' Typing row). */
val appIcons = mapOf(
    ":apps:calculator" to "bbos7_calculator", ":apps:calendar" to "bbos7_calendar", ":apps:clock" to "bbos7_clock",
    ":apps:contacts" to "bbos7_contacts", ":apps:memopad" to "bbos7_memopad", ":apps:messages" to "bbos7_messages",
    ":apps:phone" to "bbos7_phone", ":apps:pictures" to "bbos7_pictures", ":apps:tasks" to "bbos7_tasks",
    // The engine of Messages ("Messages Service") and Typing have no icon of their own on the 9900 (D).
    ":apps:hub" to "bbos7_messages", ":apps:saisie" to "opt_typing",
)

abstract class PublicApkCheck : DefaultTask() {
    @get:InputFiles abstract val apks: DirectoryProperty
    @get:InputFiles abstract val firmwareSums: ConfigurableFileCollection
    @get:InputFiles abstract val generated: ConfigurableFileCollection
    @get:Internal abstract val generatedRoot: DirectoryProperty
    @get:OutputFile abstract val report: RegularFileProperty

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The pixels of an image (fully transparent ones as 0), null if not one; [border] strips a source 9-patch's. */
    private fun pixels(b: ByteArray, border: Boolean): String? {
        val img: BufferedImage = runCatching { ImageIO.read(ByteArrayInputStream(b)) }.getOrNull() ?: return null
        val e = if (border) 1 else 0
        val w = img.width - 2 * e
        val h = img.height - 2 * e
        if (w <= 0 || h <= 0) return null
        // aapt2 turns a grey image into a grey PNG, which getRGB would pass through Java's linear grey: read its samples.
        val grey = img.colorModel.colorSpace.type == java.awt.color.ColorSpace.TYPE_GRAY
        val px = if (!grey) img.getRGB(e, e, w, h, null, 0, w) else IntArray(w * h) { i ->
            val r = img.raster
            fun s(b: Int) = r.getSample(e + i % w, e + i / w, b) * 255 / ((1 shl r.sampleModel.getSampleSize(b)) - 1)
            val a = if (r.numBands > 1) s(1) else 255
            val v = s(0)
            (a shl 24) or (v shl 16) or (v shl 8) or v
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$w x $h".toByteArray())
        for (p in px) { val q = if (p ushr 24 == 0) 0 else p; md.update(byteArrayOf((q shr 24).toByte(), (q shr 16).toByte(), (q shr 8).toByte(), q.toByte())) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun check() {
        val known = HashMap<String, String>()
        firmwareSums.files.filter { it.isFile }.forEach { f ->
            f.forEachLine { if (it.length > 66) known[it.substring(0, 64)] = "firmware " + it.substring(66) }
        }
        val root = generatedRoot.get().asFile
        val images = HashMap<String, String>()
        generated.files.filter { it.isFile }.forEach { f ->
            val name = "assets-gen/" + f.relativeTo(root).path
            val b = f.readBytes()
            known[sha(b)] = name
            if (f.name.endsWith(".png") || f.name.endsWith(".gif")) pixels(b, f.name.endsWith(".9.png"))?.let { images[it] = name }
        }
        val found = ArrayList<String>()
        val apkFiles = apks.get().asFile.listFiles { f -> f.name.endsWith(".apk") || f.name.endsWith(".zip") }.orEmpty()
        check(apkFiles.isNotEmpty()) { "no APK or zip in ${apks.get().asFile}" }
        fun scan(path: String, name: String, b: ByteArray) {
            val match = known[sha(b)]
                ?: if (name.endsWith(".png") || name.endsWith(".gif")) pixels(b, false)?.let(images::get) else null
            if (match != null) found += "$path = $match"
            if (listOf(".apk", ".zip", ".jar").any(name::endsWith)) java.util.zip.ZipInputStream(ByteArrayInputStream(b)).use { z ->
                generateSequence { z.nextEntry }.filterNot { it.isDirectory }.forEach { scan("$path!${it.name}", it.name, z.readBytes()) }
            }
        }
        for (apk in apkFiles) ZipFile(apk).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                scan("${apk.name}!${entry.name}", entry.name, zip.getInputStream(entry).use { it.readBytes() })
            }
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(found.joinToString("\n", postfix = "\n").ifBlank { "" } +
            "${apkFiles.size} archive(s) checked against ${known.size} sums and ${images.size} images\n")
        if (found.isNotEmpty()) throw GradleException("BlackBerry files in a public APK (${found.size}, $out):\n" + found.joinToString("\n"))
    }
}

/**
 * Links an RRO against the phone's own framework-res.apk (stubs/device/framework, pulled by
 * tools/stubs.sh) instead of the SDK's android.jar, in place of AGP's APK. An RRO keeps each
 * framework reference as an id, and the private ids of LineageOS are not the SDK's (measured on the
 * Q25). aapt2 takes a name from the first -I that has it and AGP puts android.jar first, so an
 * extra -I in additionalParameters changes nothing. Same sources and merged manifest, aligned, signed
 * with the project's key; a framework name the phone does not have fails the link.
 */
abstract class FrameworkLink : DefaultTask() {
    @get:InputFiles abstract val agpApk: DirectoryProperty
    @get:OutputDirectory abstract val apk: DirectoryProperty
    @get:Internal abstract val request: Property<ArtifactTransformationRequest<FrameworkLink>>
    @get:InputFile abstract val frameworkRes: RegularFileProperty
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:InputDirectory abstract val res: DirectoryProperty
    @get:Internal abstract val buildTools: DirectoryProperty
    @get:InputFile abstract val keystore: RegularFileProperty
    @get:Input abstract val keyAlias: Property<String>
    @get:Internal abstract val password: Property<String>
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun link() {
        request.get().submit(this) { built ->
            val tmp = temporaryDir.apply { deleteRecursively(); mkdirs() }
            val bt = buildTools.get().asFile
            fun run(vararg args: Any) = exec.exec {
                commandLine(*args)
                environment("BOLD7_KS_PASS", password.get())
            }
            run(bt.resolve("aapt2"), "compile", "--dir", res.get().asFile, "-o", tmp.resolve("res.zip"))
            run(bt.resolve("aapt2"), "link", "-I", frameworkRes.get().asFile, "--manifest", manifest.get().asFile,
                "-o", tmp.resolve("linked.apk"), tmp.resolve("res.zip"))
            run(bt.resolve("zipalign"), "-f", "4", tmp.resolve("linked.apk"), tmp.resolve("aligned.apk"))
            val out = apk.get().asFile.resolve(File(built.outputFile).name)
            run(bt.resolve("apksigner"), "sign", "--ks", keystore.get().asFile, "--ks-key-alias", keyAlias.get(),
                "--ks-pass", "env:BOLD7_KS_PASS", "--key-pass", "env:BOLD7_KS_PASS", "--v4-signing-enabled", "false", "--out", out, tmp.resolve("aligned.apk"))
            out
        }
    }
}

subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = 37
            defaultConfig.minSdk = 37
        }
    }
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileSdk = 37
            defaultConfig {
                minSdk = 37
                targetSdk = 37
                versionCode = 1
                versionName = "0.1"
            }
            signingConfigs.create("bold7") {
                storeFile = keystore
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystorePassword
            }
            buildTypes {
                getByName("debug") { signingConfig = signingConfigs.getByName("bold7") }
                getByName("release") {
                    isMinifyEnabled = true
                    signingConfig = signingConfigs.getByName("bold7")
                }
            }
        }
        val icon = appIcons[path]
        if (!publicBuild && icon != null) extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { v ->
                val copy = tasks.register<AppIcon>("appIcon${v.name.replaceFirstChar { it.uppercase() }}") {
                    source.set(rootProject.file("assets-gen/launcher/res/drawable-nodpi/$icon.png"))
                }
                v.sources.res?.addGeneratedSourceDirectory(copy, AppIcon::res)
            }
        }
        if (publicBuild) extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants(selector().withBuildType("release")) { v ->
                val cap = v.name.replaceFirstChar { it.uppercase() }
                val check = tasks.register<PublicApkCheck>("publicCheck$cap") {
                    apks.set(v.artifacts.get(SingleArtifact.APK))
                    firmwareSums.from(rootProject.file("assets-private/bbos7-9900/bbos7-9900.sha256").takeIf { it.isFile })
                    generatedRoot.set(rootProject.layout.projectDirectory.dir("assets-gen"))
                    generated.from(rootProject.fileTree("assets-gen"))
                    report.set(layout.buildDirectory.file("public-check/${v.name}.txt"))
                }
                tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(check) }
            }
        }
    }

    // The RROs that name framework resources: linked against the phone's framework-res (FrameworkLink).
    // The app-name RROs only hold strings of their own.
    if (path.startsWith(":system:overlays:") && name != "names") plugins.withId("com.android.application") {
        val android = extensions.getByType<ApplicationExtension>()
        val ks = keystore
        extensions.configure<ApplicationAndroidComponentsExtension> {
            val tools = sdkComponents.sdkDirectory.map { it.dir("build-tools/${android.buildToolsVersion}") }
            onVariants { v ->
                val link = tasks.register<FrameworkLink>("frameworkLink${v.name.replaceFirstChar { it.uppercase() }}") {
                    frameworkRes.set(rootProject.layout.projectDirectory.file("stubs/device/framework/framework-res.apk"))
                    manifest.set(v.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    res.set(layout.projectDirectory.dir("src/main/res"))
                    buildTools.set(tools)
                    keystore.set(ks)
                    keyAlias.set(keystoreAlias)
                    password.set(keystorePassword)
                }
                val request = v.artifacts.use(link).wiredWithDirectories(FrameworkLink::agpApk, FrameworkLink::apk)
                    .toTransformMany(SingleArtifact.APK)
                link.configure { this.request.set(request) }
            }
        }
    }
}

// The public module zip goes through the same check, archives inside included, once it is built.
if (publicBuild) project(":system:module") {
    afterEvaluate {
        val zip = tasks.named<Zip>("moduleZip")
        val check = tasks.register<PublicApkCheck>("publicCheckZip") {
            dependsOn(zip)
            apks.set(zip.flatMap { it.destinationDirectory })
            firmwareSums.from(rootProject.file("assets-private/bbos7-9900/bbos7-9900.sha256").takeIf { it.isFile })
            generatedRoot.set(rootProject.layout.projectDirectory.dir("assets-gen"))
            generated.from(rootProject.fileTree("assets-gen"))
            report.set(layout.buildDirectory.file("public-check/module-zip.txt"))
        }
        zip.configure { finalizedBy(check) }
    }
}

gradle.taskGraph.whenReady {
    // Only APK packaging needs the key: libraries, and unit tests through package*Resources,
    // must build without it.
    val packagesApk = allTasks.any { it.name.startsWith("package") && (it.name.endsWith("Debug") || it.name.endsWith("Release")) && it.project.plugins.hasPlugin("com.android.application") }
    check(!packagesApk || (keystore.exists() && keystorePassword != null)) {
        "Missing signing key: keys/bold7.jks and bold7.keystore.password in local.properties (see the README's Build section)"
    }
}
