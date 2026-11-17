package dev.bold7.sysui

import java.lang.reflect.Method

/**
 * The guard of the hooks: one table, src/main/resources/hook-targets.tsv, packed at
 * the root of the APK, read by SystemUI at its start and by tools/hook_targets.py on the Mac, with
 * the same rules. Each row names a class, a member, its parameters and its group; a group goes in
 * whole or not at all ([SysuiModule.group]).
 */
internal const val TARGETS_ENTRY = "hook-targets.tsv"

/** The group of the second scope, Google Messages' process: resolved there, never in SystemUI's. */
internal const val MESSAGES_GROUP = "messages"

/** One row of the table: [params] the parameter list "(...)" of a method, null for a field. */
internal data class TargetRow(val group: String, val kind: String, val cls: String, val member: String, val params: String?) {
    val isHook get() = kind == "hook"
    override fun toString() = "$cls.$member${params ?: ""}"
}

private val PARAMS = Regex("""\((\[*([ZBCSIJFD]|L[^;]+;))*\)""")

/** The rows of the table; a row this guard cannot read exactly is refused, never guessed. */
internal fun parseTargetRows(text: String): List<TargetRow> = text.lineSequence()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .map { line ->
        val c = line.split('\t')
        require(c.size == 5 && (c[1] == "hook" || c[1] == "uses") && (c[4] == "-" || PARAMS.matches(c[4]))) { "bad row in $TARGETS_ENTRY: $line" }
        TargetRow(c[0], c[1], c[2], c[3], c[4].takeIf { it != "-" })
    }.toList()

/** What a build declares, class by class: null when the class is not there. */
internal interface DeclaredMembers {
    /** Each declared method as its name and parameter list "(...)". */
    fun methods(cls: String): List<Pair<String, String>>?
    fun fields(cls: String): Set<String>?
}

/** The table resolved against one build: each group on or off (why), and the name each row has in it. */
internal class TargetResolution(
    val groups: List<String>,
    private val off: Map<String, String>,
    private val names: Map<TargetRow, String>,
) {
    /** Why [group] is off, null when every one of its rows was found. */
    fun off(group: String): String? = if (group in groups) off[group] else "not in $TARGETS_ENTRY"

    /** Whether [group] hooks exactly this method: its class, its name in this build, its parameters. */
    fun hooks(group: String, cls: String, name: String, params: String): Boolean =
        names.any { (row, found) -> row.group == group && row.isHook && row.cls == cls && found == name && row.params == params }

    /** The row naming [member] of [cls] and its name in this build (an R8 suffix "$*" resolved), or null. */
    fun found(cls: String, member: String): Pair<TargetRow, String>? =
        names.entries.firstOrNull { it.key.cls == cls && it.key.member == member }?.toPair()
}

/**
 * Finds each row in [members]. A method by its exact class, name and parameters (return type
 * aside); a name ending in "$*" (a suffix R8 adds and renumbers from build to build, applyState$1)
 * matches the bare name or that name and a suffix, and only when exactly one method of the class
 * matches it with the same parameters: two, and the group is off, never one taken at random. A
 * field by its class and name. One row missing turns its whole group off.
 */
internal fun resolveTargets(rows: List<TargetRow>, members: DeclaredMembers): TargetResolution {
    val names = HashMap<TargetRow, String>()
    val missing = LinkedHashMap<String, MutableList<String>>()
    for (row in rows) {
        val found = find(row, members)
        if (found.isSuccess) names[row] = found.getOrThrow() else missing.getOrPut(row.group) { ArrayList() } += found.exceptionOrNull()!!.message!!
    }
    val groups = rows.map { it.group }.distinct()
    val off = missing.mapValues { (group, why) -> "${why.size} of ${rows.count { it.group == group }} targets missing: ${why.joinToString("; ")}" }
    return TargetResolution(groups, off, names)
}

private fun find(row: TargetRow, members: DeclaredMembers): Result<String> {
    val params = row.params ?: return when (members.fields(row.cls)?.contains(row.member)) {
        true -> Result.success(row.member)
        false -> Result.failure(NoSuchFieldException("$row missing"))
        null -> Result.failure(ClassNotFoundException("${row.cls} missing"))
    }
    val methods = members.methods(row.cls) ?: return Result.failure(ClassNotFoundException("${row.cls} missing"))
    val base = row.member.removeSuffix("$*")
    val suffixed = base != row.member
    val hits = methods.filter { (name, p) -> p == params && (name == base || (suffixed && name.startsWith("$base$"))) }.map { it.first }.distinct()
    return when (hits.size) {
        1 -> Result.success(hits[0])
        0 -> Result.failure(NoSuchMethodException("$row missing"))
        else -> Result.failure(NoSuchMethodException("$row ambiguous: ${hits.sorted().joinToString()}"))
    }
}

/** The dex and JVM descriptor of a type: I, [Z, Ljava/lang/String;. */
internal fun descriptor(type: Class<*>): String = when {
    type.isArray -> type.name.replace('.', '/')
    type.isPrimitive -> when (type) {
        Int::class.javaPrimitiveType -> "I"
        Boolean::class.javaPrimitiveType -> "Z"
        Long::class.javaPrimitiveType -> "J"
        Float::class.javaPrimitiveType -> "F"
        Double::class.javaPrimitiveType -> "D"
        Byte::class.javaPrimitiveType -> "B"
        Char::class.javaPrimitiveType -> "C"
        Short::class.javaPrimitiveType -> "S"
        else -> "V"
    }
    else -> "L" + type.name.replace('.', '/') + ";"
}

internal fun params(types: Array<Class<*>>): String = types.joinToString("", "(", ")", transform = ::descriptor)

/**
 * The members of the classes [loader] sees, by reflection: SystemUI's, and the framework's through
 * it. A class that cannot be linked (a type of one of its members gone) counts as missing, said in
 * the reason, not thrown.
 */
internal class LoaderMembers(private val loader: ClassLoader) : DeclaredMembers {
    private fun <T> read(cls: String, block: (Class<*>) -> T): T? = try {
        block(Class.forName(cls, false, loader))
    } catch (e: ClassNotFoundException) {
        null
    } catch (e: LinkageError) {
        null
    }

    // Constructors are "<init>", as dexdump and the class files name them.
    override fun methods(cls: String) = read(cls) { c ->
        c.declaredMethods.map { it.name to params(it.parameterTypes) } + c.declaredConstructors.map { "<init>" to params(it.parameterTypes) }
    }
    override fun fields(cls: String) = read(cls) { c -> c.declaredFields.mapTo(HashSet()) { it.name } }
}

/** The method a row names, as resolved (its R8 suffix included), accessible; throws when it is not. */
internal fun TargetResolution.method(loader: ClassLoader, cls: String, member: String): Method {
    val (row, name) = found(cls, member) ?: throw NoSuchMethodException("$cls.$member not resolved in $TARGETS_ENTRY")
    return Class.forName(cls, false, loader).declaredMethods.single { it.name == name && params(it.parameterTypes) == row.params }
        .apply { isAccessible = true }
}

/**
 * Puts a hook in ([hook]) and gives its handle to the group going in, if any. The hook goes in
 * either way: Google Messages' process has no groups, and a safe call (groups?.installed(hook()))
 * would skip the hook itself there.
 */
internal fun <H> GroupInstaller<H>?.put(hook: () -> H): H = hook().also { this?.installed(it) }

/**
 * Puts the groups' hooks in whole or not at all ([run]). [H] is a hook's handle, [unhook] undoes
 * one. Main thread of the installation only, but [failed], which a hook running elsewhere may call:
 * only the installing thread's failures break the open group.
 */
internal class GroupInstaller<H>(private val targets: TargetResolution, private val unhook: (H) -> Unit) {
    val on = ArrayList<String>()
    /** Each group off, with why: missing in this build, or broken while it went in. */
    val off = LinkedHashMap<String, String>()
    private var open: Open<H>? = null

    private class Open<H>(val name: String, val thread: Thread) {
        val handles = ArrayList<H>()
        var broken: String? = null
    }

    /** Why the open group may not hook this method (class, name in this build, parameters), null when it may. */
    fun refused(cls: String, name: String, params: String): String? = open?.let {
        if (targets.hooks(it.name, cls, name, params)) null else "hook not in $TARGETS_ENTRY for group ${it.name}: $cls.$name$params"
    }

    fun installed(handle: H) {
        open?.handles?.add(handle)
    }

    fun failed(what: String) {
        open?.takeIf { it.thread === Thread.currentThread() && it.broken == null }?.broken = what
    }

    /**
     * Runs [block], which installs group [name]'s hooks, unless the table has it off; if anything
     * failed meanwhile ([failed]), undoes every hook it put in. The number of hooks undone.
     */
    fun run(name: String, block: () -> Unit): Int {
        targets.off(name)?.let {
            off[name] = it
            return 0
        }
        val group = Open<H>(name, Thread.currentThread())
        open = group
        try {
            block()
        } finally {
            open = null
        }
        val broken = group.broken
        if (broken == null) {
            on += name
            return 0
        }
        group.handles.forEach(unhook)
        off[name] = "$broken; ${group.handles.size} hooks undone"
        return group.handles.size
    }
}
