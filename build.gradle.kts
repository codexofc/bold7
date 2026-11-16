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
 * Fails if an APK holds a file of BlackBerry: one whose sha256 is in assets-private's list of the
 * firmware (bbos7-9900.sha256), or one of this checkout's assets-gen, by its bytes or, for an
 * image, by its pixels (aapt2 re-encodes PNGs and strips the border of 9-patches, so a copy never
 * keeps its bytes). Lists every match in its report and in the failure.
 */
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
        val apkFiles = apks.get().asFile.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
        check(apkFiles.isNotEmpty()) { "no APK in ${apks.get().asFile}" }
        for (apk in apkFiles) ZipFile(apk).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val b = zip.getInputStream(entry).use { it.readBytes() }
                val match = known[sha(b)]
                    ?: if (entry.name.endsWith(".png") || entry.name.endsWith(".gif")) pixels(b, false)?.let(images::get) else null
                if (match != null) found += "${apk.name}!${entry.name} = $match"
            }
        }
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(found.joinToString("\n", postfix = "\n").ifBlank { "" } +
            "${apkFiles.size} APK checked against ${known.size} sums and ${images.size} images\n")
        if (found.isNotEmpty()) throw GradleException("BlackBerry files in a public APK (${found.size}, $out):\n" + found.joinToString("\n"))
    }
}

subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = 34
            defaultConfig.minSdk = 34
        }
    }
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileSdk = 34
            defaultConfig {
                minSdk = 34
                targetSdk = 34
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
}

gradle.taskGraph.whenReady {
    // Only APK packaging needs the key: libraries, and unit tests through package*Resources,
    // must build without it.
    val packagesApk = allTasks.any { it.name.startsWith("package") && (it.name.endsWith("Debug") || it.name.endsWith("Release")) && it.project.plugins.hasPlugin("com.android.application") }
    check(!packagesApk || (keystore.exists() && keystorePassword != null)) {
        "Missing signing key: keys/bold7.jks and bold7.keystore.password in local.properties (see the README's Build section)"
    }
}
