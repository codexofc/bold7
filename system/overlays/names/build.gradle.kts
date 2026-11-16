plugins { id("com.android.application") }

// Static RROs of the app names (names.tsv): one product flavor, so one
// APK, per app, its strings generated as res/values*. Mounted by the KernelSU module in /system_ext/overlay
// (system/module reads the same table for the file names).
val apps = file("names.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
    it.split('\t').also { f -> check(f.size == 4) { "names.tsv: 4 columns, got $it" } }
}.groupBy { it[0] }
// Languages where the target itself translates each label (tools/names_locales.py): the RRO repeats
// the BBOS 7 name in each, otherwise the target's own translation wins over the default value.
val locales = file("names-locales.tsv").readLines().filter { it.isNotBlank() }.associate {
    val f = it.split('\t'); (f[0] to f[1]) to f[2].split(',')
}

android {
    namespace = "dev.bold7.overlay.names"
    flavorDimensions += "app"
    productFlavors {
        apps.forEach { (id, lines) ->
            create(id) {
                dimension = "app"
                applicationIdSuffix = ".$id"
                manifestPlaceholders["target"] = lines.first()[1]
            }
        }
    }
    sourceSets {
        apps.forEach { (id, lines) ->
            val dir = layout.buildDirectory.dir("generated/names/$id/res").get().asFile
            dir.deleteRecursively()
            // getValue fails the build when names-locales.tsv misses a line of names.tsv
            lines.flatMap { l -> (listOf("") + locales.getValue(l[1] to l[2])).map { q -> q to l } }.groupBy({ it.first }, { it.second }).forEach { (q, ls) ->
                File(dir, if (q.isEmpty()) "values" else "values-$q").apply { mkdirs() }.resolve("strings.xml").writeText(
                    "<resources>\n" + ls.joinToString("") { "    <string name=\"${it[2]}\">${it[3]}</string>\n" } + "</resources>\n")
            }
            maybeCreate(id).res.srcDir(dir)
        }
    }
}

// No code at all (hasCode="false"): keep out the Kotlin stdlib AGP adds to every module.
// On "implementation" only: lint's own classpath needs Kotlin.
configurations.named("implementation") { exclude(group = "org.jetbrains.kotlin") }
