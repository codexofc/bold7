plugins { id("com.android.library") }

// The package to icon table, shared by the launcher and SystemUI's module: its parsing in
// pure Kotlin, the table as a raw resource merged into both APKs. The icons themselves stay out
// of git, copied per module by tools/import-assets.sh.
android { namespace = "dev.bold7.icons" }

// The shipped tables are read by the tests (ShippedIconTableTest, ShippedAppTableTest): inputs,
// or a change to a table alone would find the tests up to date.
tasks.withType<Test>().configureEach { inputs.dir("src/main/res/raw") }

dependencies {
    testImplementation(kotlin("test-junit"))
}
