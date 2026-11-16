package dev.bold7.hub.data

/** The Hub's store in memory, for the sources' tests; HubSinkContract keeps it honest against Room. */
class FakeHubSink(private val now: () -> Long = { 0 }) : HubSink {
    val accounts = linkedMapOf<String, Account>()
    val items = linkedMapOf<Pair<String, String>, HubItem>()
    val attachments = mutableMapOf<Pair<String, String>, List<Attachment>>()
    var changes = 0
        private set

    override suspend fun putAccount(account: Account) {
        val old = accounts[account.id]
        accounts[account.id] = old?.copy(name = account.name, address = account.address) ?: account
        changes++
    }

    override suspend fun put(items: List<HubItem>) {
        items.forEach {
            check(it.accountId in accounts) { "unknown account ${it.accountId}" }
            val old = this.items[it.accountId to it.sourceKey]
            this.items[it.accountId to it.sourceKey] = it.copy(addedAt = old?.addedAt ?: now())
        }
        changes++
    }

    override suspend fun putAttachments(accountId: String, sourceKey: String, attachments: List<Attachment>) {
        this.attachments[accountId to sourceKey] = attachments
        changes++
    }

    override suspend fun item(accountId: String, sourceKey: String) = items[accountId to sourceKey]

    override suspend fun items(accountId: String) = items.values.filter { it.accountId == accountId }

    override suspend fun markRead(accountId: String, conversationIds: Collection<String>): Int {
        val unread = items.values.filter { it.accountId == accountId && !(it.read && it.sourceRead) && it.conversationId in conversationIds }
        unread.forEach { items[it.accountId to it.sourceKey] = it.copy(read = true, sourceRead = true) }
        if (unread.isNotEmpty()) changes++
        return unread.size
    }

    override suspend fun remove(accountId: String, sourceKeys: Collection<String>) {
        sourceKeys.forEach {
            items.remove(accountId to it)
            attachments.remove(accountId to it)
        }
        changes++
    }

    override suspend fun replace(accountId: String, remove: Collection<String>, put: List<HubItem>) {
        val before = changes
        remove(accountId, remove)
        if (put.isNotEmpty()) put(put)
        changes = before + 1
    }

    override suspend fun removeAccount(accountId: String) {
        accounts.remove(accountId)
        items.keys.removeAll { it.first == accountId }
        attachments.keys.removeAll { it.first == accountId }
        changes++
    }
}
