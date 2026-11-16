package dev.bold7.launcher.hub

import dev.bold7.hub.data.HubItem
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.withoutEchoes

/**
 * The items opening [row] reads: its whole conversation up to the item the list showed (not a
 * message come since), and the notifications the list hides as repeating them (withoutEchoes), so
 * that the app's own action reads them (Google Messages) and Android's shade loses them (D7).
 * Never a deleted item.
 */
fun readKeys(row: HubItem, snapshot: HubSnapshot): List<ItemKey> {
    val items = snapshot.items.filter { !it.deleted }
    val targets = items.filter { it.sameRow(row) && it.timestamp <= row.timestamp }
    if (targets.isEmpty()) return emptyList()
    // The echoes of the targets: hidden with them in the list, shown without them.
    val hidden = snapshot.items.withoutEchoes(snapshot.smsApp, snapshot.dialer).toHashSet()
    val echoes = (snapshot.items - targets.toSet()).withoutEchoes(snapshot.smsApp, snapshot.dialer).filter { it !in hidden && !it.deleted }
    return (targets + echoes).map { ItemKey(it.accountId, it.sourceKey) }
}

private fun HubItem.sameRow(other: HubItem) =
    accountId == other.accountId && if (conversationId == null) sourceKey == other.sourceKey else conversationId == other.conversationId
