import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins { id("com.android.library") }

// The extractor of the installer: reads the official file of
// the Bold 9900 and writes the assets Bold7 draws with. Pure Kotlin and the JDK, no android.*: the
// module's customize.sh runs its main() in root through app_process.
android {
    namespace = "dev.bold7.firmware"
    // The tests that read the real firmware find it, and this checkout's assets-gen/, from here;
    // they are skipped when the firmware is missing.
    testOptions.unitTests.all { it.systemProperty("bold7.root", rootDir.path) }
}

dependencies {
    testImplementation(kotlin("test-junit"))
}

/**
 * The extractor as one dex jar for app_process (build/dex/bold7-firmware.jar): this module's
 * release classes and its runtime libraries (Kotlin's standard library), by d8 of the SDK's
 * build-tools. No shrinking: the jar is read once, at install.
 */
abstract class ExtractorDex : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:InputFiles abstract val jars: ListProperty<RegularFile>
    @get:InputFiles abstract val dirs: ListProperty<Directory>
    @get:InputFiles abstract val libraries: ConfigurableFileCollection
    @get:InputFile abstract val d8: RegularFileProperty
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction fun dex() {
        val classes = dirs.get().flatMap { d -> d.asFile.walkTopDown().filter { it.name.endsWith(".class") }.toList() }
        output.get().asFile.delete()
        exec.exec {
            commandLine(listOf(d8.get().asFile.path, "--release", "--min-api", "34", "--output", output.get().asFile.path) +
                jars.get().map { it.asFile.path } + classes.map { it.path } + libraries.files.map { it.path })
        }
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { v ->
    val dex = tasks.register<ExtractorDex>("extractorDex") {
        libraries.from(v.runtimeConfiguration)
        d8 = androidComponents.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/d8") }
        output = layout.buildDirectory.file("dex/bold7-firmware.jar")
    }
    v.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(dex).toGet(ScopedArtifact.CLASSES, ExtractorDex::jars, ExtractorDex::dirs)
}

/**
 * tools/import-assets.sh help: the help decoded on the Mac by the code the phone runs, from the OS
 * Loader (-Ploader) into -Pout. The development build only; a user's phone decodes its own.
 */
tasks.register<JavaExec>("decodeHelp") {
    dependsOn("compileReleaseKotlin")
    classpath = files(tasks.named("compileReleaseKotlin").map { it.outputs.files }) + configurations.getByName("releaseRuntimeClasspath")
    mainClass = "dev.bold7.firmware.ExtractorKt"
    args = listOf("help", providers.gradleProperty("loader").getOrElse(""), providers.gradleProperty("out").getOrElse(""))
}
