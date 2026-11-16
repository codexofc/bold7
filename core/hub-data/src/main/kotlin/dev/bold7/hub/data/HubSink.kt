package dev.bold7.hub.data

/**
 * What a source of the Hub (SMS and MMS, call log, notifications, mail) writes into. The source
 * owns what it reads; the Hub owns what the user set. Every write is announced to the readers of
 * the counters, without polling.
 */
interface HubSink {
    /**
     * Adds the account, or renames it: its colour, position and last visit stay the Hub's. Its id
     * is opaque (no address, no number): it leaves the process with the counters.
     */
    suspend fun putAccount(account: Account)

    /** Adds or rewrites items of known accounts, by (accountId, sourceKey); their attachments stay. */
    suspend fun put(items: List<HubItem>)

    /** Replaces the attachments known for one item. */
    suspend fun putAttachments(accountId: String, sourceKey: String, attachments: List<Attachment>)

    /** One item as stored, or null: what a notification's update is compared to. */
    suspend fun item(accountId: String, sourceKey: String): HubItem?

    /** The items of one account as stored, with their arrival: what a source compares its rows to. */
    suspend fun items(accountId: String): List<HubItem>

    /**
     * The source has read these conversations (a notification removed): their items are read for
     * it and for the Hub (HubItem.sourceRead, HubItem.read). Returns how many changed, announced
     * only when some did.
     */
    suspend fun markRead(accountId: String, conversationIds: Collection<String>): Int

    /** Forgets items, attachments included; unknown keys are ignored. */
    suspend fun remove(accountId: String, sourceKeys: Collection<String>)

    /**
     * [remove] then [put], in one transaction, announced once: a mail folder's sync,
     * never seen half applied.
     */
    suspend fun replace(accountId: String, remove: Collection<String>, put: List<HubItem>)

    /** Forgets the account and every item of it: a mail account the user deleted. */
    suspend fun removeAccount(accountId: String)
}
