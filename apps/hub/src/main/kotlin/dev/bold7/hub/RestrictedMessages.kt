package dev.bold7.hub

import androidx.annotation.Keep

/**
 * Android 17 marks the SMS and MMS rows that the SMS app did not write itself as restricted
 * (read_restriction): only the SMS app reads them. A Google backup restored at the phone's setup
 * writes the whole history so, and the Hub, with READ_SMS, read an empty database. Run in root by
 * system/module/src/lift.sh through app_process (classpath: this APK and content.jar), it lifts
 * the restriction of the rows named on stdin, one "content://sms <_id>" or "content://mms <_id>"
 * a line, the way `content update` does (the provider takes the restriction only by one _id), in
 * one process: a JVM started per row would take hours for a history of 30,000 messages. Nothing
 * else of a row is written. Prints how many rows it was asked.
 */
@Keep
object RestrictedMessages {
    @JvmStatic
    fun main(args: Array<String>) {
        val providers = HashMap<String, (Long) -> Unit>()
        var asked = 0
        var left = 0
        var why: String? = null
        while (true) {
            val line = readLine() ?: break
            val (uri, id) = liftedRow(line) ?: continue
            val lift = providers.getOrPut(uri) { direct(uri) ?: viaContent(uri) }
            asked++
            // One row that fails never ends the run (a user's lifter died on the first locked row).
            if (!liftRetrying(onLeft = { why = it }) { lift(id) }) left++
        }
        println(asked)
        if (left > 0) System.err.println("$left of $asked rows left restricted: $why")
    }

    /**
     * The provider of [uri] taken once, its update called for each row, as `content update` calls
     * it (the same IContentProvider.update, as root): 33 ms a row through Content.main, which takes
     * and releases the provider each time. Null when this Android's hidden API differs: Content.main then.
     */
    private fun direct(uri: String): ((Long) -> Unit)? = try {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val token = android.os.Binder()
        val authority = android.net.Uri.parse(uri).authority
        val holder = am.javaClass.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, android.os.IBinder::class.java, String::class.java)
            .invoke(am, authority, 0, token, "bold7")
        val provider = holder!!.javaClass.getField("provider").get(holder)!!
        val update = provider.javaClass.methods.single { m ->
            m.name == "update" && m.parameterTypes.size == 4 && m.parameterTypes[0] == android.content.AttributionSource::class.java
        }
        val caller = android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("root").build()
        val target = android.net.Uri.parse(uri)
        val values = android.content.ContentValues().apply { put("restricted", 0) }
        ({ id: Long ->
            val where = android.os.Bundle().apply { putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "_id=$id") }
            update.invoke(provider, caller, target, values, where)
        })
    } catch (e: Exception) {
        System.err.println("direct update unavailable: ${e.javaClass.name}")
        null
    }

    private fun viaContent(uri: String): (Long) -> Unit {
        val content = Class.forName("com.android.commands.content.Content").getMethod("main", Array<String>::class.java)
        return { id -> content.invoke(null, liftArgs(uri, id)) }
    }
}

/**
 * [lift] for one row, tried again while the provider answers that its database is locked
 * (SQLITE_BUSY: Google Messages writing, or the Hub reading a long history at that moment), [pause]
 * growing between tries; true once done. A row still locked after [tries], or failing otherwise, is
 * left (false), its reason to [onLeft]: the run goes on.
 */
internal fun liftRetrying(
    tries: Int = 6,
    pause: (Int) -> Unit = { Thread.sleep(it * 250L) },
    onLeft: (String) -> Unit = {},
    lift: () -> Unit,
): Boolean {
    for (attempt in 1..tries) {
        try {
            lift()
            return true
        } catch (e: Exception) {
            val cause = generateSequence<Throwable>(e) { it.cause }.last()
            val busy = generateSequence<Throwable>(e) { it.cause }.any { it.message.orEmpty().contains("database is locked") }
            if (!busy || attempt == tries) {
                onLeft("${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(120)}")
                return false
            }
            pause(attempt)
        }
    }
    return false
}

private val ROW = Regex("(content://(?:sms|mms)) ([0-9]{1,18})")

/** The row a line names, only an SMS or an MMS row by its _id; null for anything else. */
internal fun liftedRow(line: String): Pair<String, Long>? =
    ROW.matchEntire(line.trim())?.let { it.groupValues[1] to it.groupValues[2].toLong() }

/** `content update` lifting the restriction of row [id] of [uri], as tools/restore-messages.sh does. */
internal fun liftArgs(uri: String, id: Long) = arrayOf("update", "--uri", uri, "--bind", "restricted:i:0", "--where", "_id=$id")
