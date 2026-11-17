package dev.bold7.sysui

import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetTableTest {
    private fun members(vararg classes: Pair<String, List<String>>) = object : DeclaredMembers {
        val map = classes.toMap()
        override fun methods(cls: String) = map[cls]?.filter { '(' in it }?.map { it.substringBefore('(') to "(" + it.substringAfter('(') }
        override fun fields(cls: String) = map[cls]?.filterNot { '(' in it }?.toSet()
    }

    private fun table(vararg rows: String) = parseTargetRows(rows.joinToString("\n") { it.replace(' ', '\t') })

    @Test fun rowsAreReadExactlyOrRefused() {
        val rows = table("# comment", "", "g hook a.B m (ILjava/lang/String;[Z)", "g uses a.B f -")
        assertEquals(listOf(TargetRow("g", "hook", "a.B", "m", "(ILjava/lang/String;[Z)"), TargetRow("g", "uses", "a.B", "f", null)), rows)
        // No arity, no partial descriptor, no third kind: the guard never guesses a signature.
        for (bad in listOf("g hook a.B m #3", "g hook a.B m (I", "g hook a.B m (Ljava/lang/String)", "g call a.B m ()", "g hook a.B m")) {
            assertFailsWith<IllegalArgumentException>(bad) { table(bad) }
        }
    }

    @Test fun aGroupIsOnOnlyWhenEveryRowIsFound() {
        val build = members("a.B" to listOf("m(I)", "f"), "a.C" to listOf("n()"))
        val r = resolveTargets(table("on hook a.B m (I)", "on uses a.B f -", "off hook a.C n ()", "off uses a.C gone -"), build)
        assertEquals(listOf("on", "off"), r.groups)
        assertNull(r.off("on"))
        assertEquals("1 of 2 targets missing: a.C.gone missing", r.off("off"))
        assertEquals("not in $TARGETS_ENTRY", r.off("other"))
    }

    // Same name, other parameters, other class: missing, never the nearest method.
    @Test fun classNameAndParametersAreExact() {
        val build = members("a.B" to listOf("m(J)", "m(IZ)"), "a.Sub" to listOf("m(I)"))
        val r = resolveTargets(table("g hook a.B m (I)", "h hook a.Gone m (I)", "i uses a.Gone f -"), build)
        assertEquals("1 of 1 targets missing: a.B.m(I) missing", r.off("g"))
        assertEquals("1 of 1 targets missing: a.Gone missing", r.off("h"))
        assertEquals("1 of 1 targets missing: a.Gone missing", r.off("i"))
    }

    // R8's suffixes: applyState$1 this week, applyState$2 or applyState the next.
    @Test fun anR8SuffixResolvesOnlyToTheOneMethodWithTheseParameters() {
        val rows = table("g hook a.B applyState$* ()")
        for (name in listOf("applyState$1", "applyState$4", "applyState", "applyState\$frameworks__base__SystemUI_core")) {
            val r = resolveTargets(rows, members("a.B" to listOf("$name()", "applyState$2(I)", "applyStateLater()")))
            assertNull(r.off("g"), name)
            assertEquals(name, r.found("a.B", "applyState$*")!!.second)
            assertTrue(r.hooks("g", "a.B", name, "()"))
        }
        val two = resolveTargets(rows, members("a.B" to listOf("applyState$1()", "applyState$3()")))
        assertEquals("1 of 1 targets missing: a.B.applyState$*() ambiguous: applyState$1, applyState$3", two.off("g"))
        assertNull(two.found("a.B", "applyState$*"))
        // No suffix in the row: the bare name only.
        assertEquals("1 of 1 targets missing: a.B.applyState() missing", resolveTargets(table("g hook a.B applyState ()"), members("a.B" to listOf("applyState$1()"))).off("g"))
    }

    @Test fun onlyTheGroupsOwnResolvedHooksCount() {
        val r = resolveTargets(table("g hook a.B m$* (I)", "g uses a.B f -", "h hook a.B n ()"), members("a.B" to listOf("m$2(I)", "n()", "f")))
        assertTrue(r.hooks("g", "a.B", "m$2", "(I)"))
        assertFalse(r.hooks("g", "a.B", "m$2", "(J)"))
        assertFalse(r.hooks("g", "a.B", "n", "()"))
        assertFalse(r.hooks("g", "a.B", "f", "()"))
        assertTrue(r.hooks("h", "a.B", "n", "()"))
    }

    // A group goes in whole or not at all, and each one off says why.
    @Test fun aGroupBrokenWhileItGoesInIsUndoneWhole() {
        val r = resolveTargets(table("a hook x.A m ()", "b hook x.A n ()", "b hook x.A o ()", "c hook x.A gone ()"), members("x.A" to listOf("m()", "n()", "o()")))
        val undone = ArrayList<String>()
        val groups = GroupInstaller<String>(r) { undone += it }
        assertEquals(0, groups.run("a") { assertNull(groups.refused("x.A", "m", "()")); groups.installed("m") })
        // b: n goes in, o is refused by Vector (or its member is missing): n is undone.
        assertEquals(1, groups.run("b") {
            groups.installed("n")
            groups.failed("hook refused by Vector: x.A.o")
            groups.failed("a second failure keeps the first reason")
        })
        assertEquals(listOf("n"), undone)
        // c is off before anything goes in; d is not in the table at all.
        assertEquals(0, groups.run("c") { error("not run") })
        assertEquals(0, groups.run("d") { error("not run") })
        assertEquals(listOf("a"), groups.on)
        assertEquals(mapOf(
            "b" to "hook refused by Vector: x.A.o; 1 hooks undone",
            "c" to "1 of 1 targets missing: x.A.gone() missing",
            "d" to "not in $TARGETS_ENTRY",
        ), groups.off)
    }

    // The hook goes in with or without groups: Google Messages' process has none, and its bridge
    // counted hooks it never put in.
    @Test fun aHookGoesInEvenWithoutGroups() {
        var put = 0
        assertEquals("h", (null as GroupInstaller<String>?).put { put++; "h" })
        assertEquals(1, put)
        val r = resolveTargets(table("a hook x.A m ()"), members("x.A" to listOf("m()")))
        val undone = ArrayList<String>()
        val groups = GroupInstaller<String>(r) { undone += it }
        groups.run("a") { groups.put { put++; "m" }; groups.failed("x") }
        assertEquals(2, put)
        assertEquals(listOf("m"), undone)
    }

    // The code cannot hook what the table does not give its group; outside a group, nothing is checked.
    @Test fun aHookOutsideItsGroupsRowsIsRefused() {
        val r = resolveTargets(table("a hook x.A m ()", "b hook x.A n ()"), members("x.A" to listOf("m()", "n()")))
        val groups = GroupInstaller<String>(r) {}
        assertNull(groups.refused("x.A", "n", "()"))
        groups.run("a") {
            assertEquals("hook not in $TARGETS_ENTRY for group a: x.A.n()", groups.refused("x.A", "n", "()"))
            assertEquals("hook not in $TARGETS_ENTRY for group a: x.A.m(I)", groups.refused("x.A", "m", "(I)"))
        }
    }

    // A hook failing on another thread while a group goes in is counted by the module, not this group's.
    @Test fun onlyTheInstallingThreadBreaksTheGroup() {
        val r = resolveTargets(table("a hook x.A m ()"), members("x.A" to listOf("m()")))
        val groups = GroupInstaller<String>(r) {}
        groups.run("a") { Thread { groups.failed("splash thread") }.apply { start(); join() } }
        assertEquals(listOf("a"), groups.on)
    }

    // What SystemUI's process reads: reflection on the classes of a class loader.
    @Test fun reflectionGivesDescriptorsAndFields() {
        val jvm = LoaderMembers(javaClass.classLoader!!)
        assertContains(jvm.methods("java.lang.String")!!, "regionMatches" to "(ZILjava/lang/String;II)")
        assertContains(jvm.methods("java.lang.String")!!, "getChars" to "(II[CI)")
        assertContains(jvm.fields("java.lang.String")!!, "value")
        assertNull(jvm.methods("no.Such"))
        assertNull(jvm.fields("no.Such"))
        val r = resolveTargets(table("g hook java.lang.String regionMatches (ZILjava/lang/String;II)"), jvm)
        assertEquals("regionMatches", r.method(javaClass.classLoader!!, "java.lang.String", "regionMatches").name)
        assertFailsWith<NoSuchMethodException> { r.method(javaClass.classLoader!!, "java.lang.String", "length") }
    }

    // The table against LineageOS 24's own SystemUI and framework (stubs/, tools/stubs.sh): every group
    // L5 retargeted is whole; the ones whose hooks are gone stay off, for the L6 lots.
    @Test fun lineageGroupsAreWholeButTheOnesL6Rewrites() {
        val rows = parseTargetRows(File("src/main/resources/$TARGETS_ENTRY").readText())
        val r = resolveTargets(rows.filter { it.group != MESSAGES_GROUP }, JarMembers(File("../../stubs/systemui.jar"), File("../../stubs/framework.jar")))
        val off = r.groups.filter { r.off(it) != null }
        assertEquals(setOf("placecall", "splash"), off.toSet(), off.joinToString("\n") { "$it: ${r.off(it)}" })
        assertNull(resolveTargets(rows.filter { it.group == MESSAGES_GROUP }, JarMembers(File("../../stubs/framework.jar"))).off(MESSAGES_GROUP))
    }
}

/**
 * The members of the classes of stub jars, read from their class files: the stubs have no code,
 * the JVM would refuse to load them. Only the constant pool, the fields and the methods.
 */
internal class JarMembers(vararg jars: File) : DeclaredMembers {
    private val zips = jars.map(::ZipFile)
    private val cache = HashMap<String, Pair<List<Pair<String, String>>, Set<String>>?>()

    private fun read(cls: String) = cache.getOrPut(cls) {
        val (zip, entry) = zips.firstNotNullOfOrNull { z -> z.getEntry(cls.replace('.', '/') + ".class")?.let { z to it } } ?: return@getOrPut null
        DataInputStream(zip.getInputStream(entry).buffered()).use { parse(it) }
    }

    override fun methods(cls: String) = read(cls)?.first
    override fun fields(cls: String) = read(cls)?.second

    private fun parse(d: DataInputStream): Pair<List<Pair<String, String>>, Set<String>> {
        d.readInt(); d.readUnsignedShort(); d.readUnsignedShort()
        val count = d.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        var i = 1
        while (i < count) {
            when (d.readUnsignedByte()) {
                1 -> utf8[i] = d.readUTF()
                3, 4 -> d.readInt()
                5, 6 -> { d.readLong(); i++ }
                7, 8, 16, 19, 20 -> d.readUnsignedShort()
                9, 10, 11, 12, 17, 18 -> d.readInt()
                15 -> { d.readUnsignedByte(); d.readUnsignedShort() }
                else -> error("constant pool tag")
            }
            i++
        }
        d.readUnsignedShort(); d.readUnsignedShort(); d.readUnsignedShort()
        repeat(d.readUnsignedShort()) { d.readUnsignedShort() }
        fun members(): List<Pair<String, String>> = List(d.readUnsignedShort()) {
            d.readUnsignedShort()
            val name = utf8[d.readUnsignedShort()]!!
            val desc = utf8[d.readUnsignedShort()]!!
            repeat(d.readUnsignedShort()) { d.readUnsignedShort(); d.skipBytes(d.readInt()) }
            name to desc
        }
        val fields = members().mapTo(HashSet()) { it.first }
        val methods = members().map { (name, desc) -> name to desc.substringBefore(')') + ")" }
        return methods to fields
    }
}
