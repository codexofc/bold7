package dev.bold7.hub.data

// The sources the user turned off in Messages Options: an account (Text Messages, Calls, Calendar,
// a mail account, by its id) or another app's notifications (by its package). Messages keeps the
// choice; the Hub reads it through Messages' provider and stores nothing of a source turned off.

/**
 * Messages' provider of the choice: call([METHOD_DISABLED_SOURCES]) answers [EXTRA_DISABLED], the
 * sources off, behind HubContract.PERMISSION; a change is notified on content://[SOURCES_AUTHORITY].
 */
const val SOURCES_AUTHORITY = "dev.bold7.messages.sources"
const val METHOD_DISABLED_SOURCES = "disabled"
const val EXTRA_DISABLED = "disabled"

/** Off until the user turns it on: Signal (the maintainer's choice). */
val DEFAULT_DISABLED_SOURCES = setOf("org.thoughtcrime.securesms")

/** Whether [item] comes from one of the sources [off]. */
fun HubItem.fromSourceIn(off: Set<String>) = accountId in off || (kind == ItemKind.NOTIFICATION && folder != null && folder in off)

/**
 * [sink] that drops what the sources [off] write, for the writers HubSources does not hold (mail).
 * Removals, reads and accounts pass: a source turned off stays listed, to be turned on again.
 */
class GatedSink(private val sink: HubSink, private val off: () -> Set<String>) : HubSink by sink {
    override suspend fun put(items: List<HubItem>) {
        val off = off()
        val kept = items.filterNot { it.fromSourceIn(off) }
        if (kept.isNotEmpty()) sink.put(kept)
    }

    override suspend fun replace(accountId: String, remove: Collection<String>, put: List<HubItem>) {
        val off = off()
        sink.replace(accountId, remove, put.filterNot { it.fromSourceIn(off) })
    }

    // The item is not stored: its attachments would have nothing to belong to.
    override suspend fun putAttachments(accountId: String, sourceKey: String, attachments: List<Attachment>) {
        if (accountId !in off()) sink.putAttachments(accountId, sourceKey, attachments)
    }
}

/**
 * Forgets every item of the sources [off]: an account's (an id of [off] that has items), and the
 * notifications of an app of [off]. Returns how many.
 */
suspend fun HubSink.forget(off: Set<String>): Int {
    var forgotten = 0
    for (id in off) {
        val keys = items(id).map { it.sourceKey }
        if (keys.isNotEmpty()) remove(id, keys).also { forgotten += keys.size }
    }
    val apps = items(HubAccounts.NOTIFICATIONS.id).filter { it.fromSourceIn(off) }.map { it.sourceKey }
    if (apps.isNotEmpty()) remove(HubAccounts.NOTIFICATIONS.id, apps).also { forgotten += apps.size }
    return forgotten
}
