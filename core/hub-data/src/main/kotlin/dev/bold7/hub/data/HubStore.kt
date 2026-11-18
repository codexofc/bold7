package dev.bold7.hub.data

import android.content.Context
import android.net.Uri
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The Hub's database. Each version adds its Migration, tested against the schema exported in
 * core/hub-data/schemas. No destructive fallback: a missing migration crashes rather than lose the
 * Hub's trace of notifications gone from Android. Version 2: HubItem.sourceRead
 * and HubItem.deleted ([From1To2]). Version 3: HubItem.category and
 * HubItem.messaging, added empty (the category of an older row is unknown).
 */
@Database(
    entities = [Account::class, HubItem::class, Attachment::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = From1To2::class), AutoMigration(from = 2, to = 3)],
)
abstract class HubDatabase : RoomDatabase() {
    internal abstract fun dao(): HubDao

    companion object {
        @Volatile private var instance: HubDatabase? = null

        /**
         * The process's database, in the app's credential-encrypted storage: unreadable until the
         * first unlock after a boot. Opens on the first query, never on the main thread.
         */
        fun get(context: Context): HubDatabase = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                check(!app.isDeviceProtectedStorage) { "The Hub's database stays in credential-encrypted storage" }
                Room.databaseBuilder(app, HubDatabase::class.java, "hub.db").build().also { instance = it }
            }
        }
    }
}

/**
 * Version 1's read state was its source's: it is the source's state of version 2 too, else every
 * read SMS and call would be rewritten at its next read, and the first listener binding would
 * mark the whole trace of removed notifications read again. Nothing deleted before version 2.
 * Room's generated migration calls the connection's form, the phone's database wrapped in one.
 */
internal class From1To2 : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) = connection.execSQL(COPY)

    private companion object {
        const val COPY = "UPDATE item SET sourceRead = read"
    }
}

/** The store of the process that owns the database (apps/hub), announcing each write to the counters' readers. */
fun hubStore(context: Context): HubStore {
    val resolver = context.applicationContext.contentResolver
    val counts = Uri.parse(HubContract.COUNTS_URI)
    return HubStore(HubDatabase.get(context), System::currentTimeMillis) { resolver.notifyChange(counts, null) }
}

// Reached through HubStore only, which announces each write.
@Dao
internal abstract class HubDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertAccount(account: Account)

    @Query("UPDATE account SET name = :name, address = :address WHERE id = :id")
    protected abstract suspend fun renameAccount(id: String, name: String, address: String?)

    @Transaction
    open suspend fun putAccount(account: Account) {
        insertAccount(account)
        renameAccount(account.id, account.name, account.address)
    }

    @Query("UPDATE account SET seenAt = :at WHERE id = :id") abstract suspend fun markSeen(id: String, at: Long)

    // Blocking, as the provider's: on its binder thread. Never back in time.
    @Query("UPDATE account SET seenAt = :upTo WHERE id = :id AND seenAt < :upTo")
    abstract fun markSeenUpTo(id: String, upTo: Long): Int

    @Query("UPDATE account SET color = :color, position = :position WHERE id = :id")
    abstract suspend fun setLook(id: String, color: AccountColor, position: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertItems(items: List<HubItem>)

    @Update protected abstract suspend fun updateItems(items: List<HubItem>)

    @Query("SELECT addedAt FROM item WHERE accountId = :accountId AND sourceKey = :sourceKey")
    protected abstract suspend fun addedAt(accountId: String, sourceKey: String): Long?

    // Updated in place: a REPLACE would delete the row first, and its attachments with it. Not
    // @Upsert, which recognises the conflict by the exception's message, lost on the JVM tests' SQLite.
    @Transaction
    open suspend fun put(items: List<HubItem>, now: Long) {
        val stamped = items.map { it.copy(addedAt = addedAt(it.accountId, it.sourceKey) ?: now) }
        updateItems(stamped)
        insertItems(stamped)
    }

    @Query("DELETE FROM attachment WHERE accountId = :accountId AND sourceKey = :sourceKey")
    protected abstract suspend fun deleteAttachments(accountId: String, sourceKey: String)

    @Insert protected abstract suspend fun insertAttachments(attachments: List<Attachment>)

    @Transaction
    open suspend fun putAttachments(accountId: String, sourceKey: String, attachments: List<Attachment>) {
        deleteAttachments(accountId, sourceKey)
        insertAttachments(attachments)
    }

    @Query(
        "UPDATE item SET read = 1, sourceRead = 1 WHERE accountId = :accountId AND (read = 0 OR sourceRead = 0) " +
            "AND conversationId IN (:conversationIds)",
    )
    abstract suspend fun markRead(accountId: String, conversationIds: Collection<String>): Int

    // Blocking, as the provider's: on its binder thread, in a transaction.
    @Query("UPDATE item SET read = :read WHERE accountId = :accountId AND read != :read AND sourceKey IN (:sourceKeys)")
    abstract fun setReadNow(accountId: String, sourceKeys: Collection<String>, read: Boolean): Int

    @Query("UPDATE item SET deleted = 1 WHERE accountId = :accountId AND deleted = 0 AND sourceKey IN (:sourceKeys)")
    abstract fun deleteNow(accountId: String, sourceKeys: Collection<String>): Int

    @Query("DELETE FROM item WHERE accountId = :accountId AND sourceKey IN (:sourceKeys)")
    abstract suspend fun remove(accountId: String, sourceKeys: Collection<String>)

    @Transaction
    open suspend fun replace(accountId: String, remove: Collection<String>, put: List<HubItem>, now: Long) {
        // SQLite's limit of 999 variables per statement: a new UIDVALIDITY removes up to 500 a folder.
        remove.chunked(500).forEach { remove(accountId, it) }
        if (put.isNotEmpty()) put(put, now)
    }

    // The items and their attachments go with it (ForeignKey.CASCADE).
    @Query("DELETE FROM account WHERE id = :id") abstract suspend fun removeAccount(id: String)

    @Query("SELECT * FROM attachment WHERE accountId = :accountId AND sourceKey = :sourceKey ORDER BY position")
    abstract suspend fun attachments(accountId: String, sourceKey: String): List<Attachment>

    @Query("SELECT * FROM account ORDER BY position") abstract suspend fun accounts(): List<Account>

    @Query("SELECT * FROM item") abstract suspend fun items(): List<HubItem>

    @Query("SELECT * FROM item WHERE accountId = :accountId") abstract suspend fun items(accountId: String): List<HubItem>

    @Query("SELECT * FROM item WHERE accountId = :accountId AND sourceKey = :sourceKey")
    abstract suspend fun item(accountId: String, sourceKey: String): HubItem?

    @Query("SELECT * FROM item WHERE accountId = :accountId AND conversationId IN (:conversationIds)")
    abstract fun conversationsNow(accountId: String, conversationIds: Collection<String>): List<HubItem>

    // Blocking: the provider answers on a binder thread. Room refuses them on the main thread.
    @Query("SELECT * FROM account ORDER BY position") abstract fun accountsNow(): List<Account>

    @Query("SELECT * FROM item WHERE read = 0 AND box = 'INBOX' AND deleted = 0") abstract fun unreadNow(): List<HubItem>

    @Query("SELECT * FROM item") abstract fun itemsNow(): List<HubItem>

    @Query("SELECT * FROM item WHERE accountId = :accountId AND kind = 'NOTIFICATION' AND conversationId = :key")
    abstract fun conversationNow(accountId: String, key: String): List<HubItem>

    @Query("SELECT EXISTS (SELECT 1 FROM item WHERE accountId = :accountId AND kind = 'NOTIFICATION' AND conversationId = :key)")
    abstract fun holdsNotificationNow(accountId: String, key: String): Boolean
}

/** [now] stamps an item's arrival (HubItem.addedAt); [onChanged] follows each write. */
class HubStore(private val db: HubDatabase, private val now: () -> Long, private val onChanged: () -> Unit) : HubSink {
    private val dao = db.dao()

    override suspend fun putAccount(account: Account) = dao.putAccount(account).also { onChanged() }
    override suspend fun put(items: List<HubItem>) = dao.put(items, now()).also { onChanged() }
    override suspend fun putAttachments(accountId: String, sourceKey: String, attachments: List<Attachment>) =
        dao.putAttachments(accountId, sourceKey, attachments).also { onChanged() }
    override suspend fun item(accountId: String, sourceKey: String) = dao.item(accountId, sourceKey)
    override suspend fun items(accountId: String) = dao.items(accountId)
    override suspend fun markRead(accountId: String, conversationIds: Collection<String>) =
        dao.markRead(accountId, conversationIds).also { if (it > 0) onChanged() }
    override suspend fun remove(accountId: String, sourceKeys: Collection<String>) =
        dao.remove(accountId, sourceKeys).also { onChanged() }
    override suspend fun replace(accountId: String, remove: Collection<String>, put: List<HubItem>) =
        dao.replace(accountId, remove, put, now()).also { onChanged() }
    override suspend fun removeAccount(accountId: String) = dao.removeAccount(accountId).also { onChanged() }

    /** The Hub has shown this account up to [at]: its unread items before are no longer new. */
    suspend fun markSeen(accountId: String, at: Long) = dao.markSeen(accountId, at).also { onChanged() }

    /**
     * The user saw each account of [upTo] in the Hub up to the arrival it maps to
     * (HubContract.METHOD_MARK_SEEN), never back: returns how many changed, announced only when
     * some did. No clock: the arrivals are the store's own. Blocking: the provider's binder thread.
     */
    fun markSeen(upTo: Map<String, Long>): Int {
        val changed = db.runInTransaction<Int> { upTo.entries.sumOf { (id, at) -> dao.markSeenUpTo(id, at) } }
        if (changed > 0) onChanged()
        return changed
    }

    /**
     * Marked [read] or unread in the Hub: the Hub's state only, the source's stays
     * (HubItem.sourceRead). Returns how many changed, announced only when some did. Blocking: the
     * provider's binder thread.
     */
    fun setRead(keys: Collection<ItemKey>, read: Boolean): Int = byAccount(keys) { account, sourceKeys -> dao.setReadNow(account, sourceKeys, read) }

    /** Deleted in the Hub, kept as a mark (HubItem.deleted). As [setRead]. */
    fun delete(keys: Collection<ItemKey>): Int = byAccount(keys) { account, sourceKeys -> dao.deleteNow(account, sourceKeys) }

    private fun byAccount(keys: Collection<ItemKey>, write: (String, List<String>) -> Int): Int {
        val changed = db.runInTransaction<Int> {
            keys.groupBy({ it.accountId }, { it.sourceKey }).entries.sumOf { (account, sourceKeys) -> write(account, sourceKeys) }
        }
        if (changed > 0) onChanged()
        return changed
    }

    /** The user's colour and place for the account (Hub settings). */
    suspend fun setLook(accountId: String, color: AccountColor, position: Int) =
        dao.setLook(accountId, color, position).also { onChanged() }

    suspend fun attachments(accountId: String, sourceKey: String) = dao.attachments(accountId, sourceKey)

    suspend fun accounts() = dao.accounts()
    suspend fun items() = dao.items()

    /**
     * The rows of HubContract's cursor: the Hub's total, then each account (id, kind, unread,
     * new, seen). Blocking, off the main thread; reads the unread items only.
     */
    fun counterRows(): List<Array<Any?>> = db.runInTransaction<List<Array<Any?>>> {
        val accounts = dao.accountsNow()
        val counts = counts(accounts, dao.unreadNow())
        listOf(arrayOf<Any?>(null, null, counts.total.unread, counts.total.new, null)) +
            accounts.sortedBy { it.position }.map { a ->
                val c = counts.byAccount.getValue(a.id)
                arrayOf(a.id, a.kind.name, c.unread, c.new, a.seenAt)
            }
    }

    /** The rows of [HubContract.KINDS_URI]: (kind, unread, null) for each kind with unread items, then (NOTIFICATION, unread, app) for each other app. Blocking. */
    fun kindRows(smsApp: String?, dialer: String?): List<Array<Any?>> = db.runInTransaction<List<Array<Any?>>> {
        val accounts = dao.accountsNow()
        val items = dao.itemsNow()
        unreadByKind(accounts, items, smsApp, dialer).map { (kind, n) -> arrayOf<Any?>(kind.name, n, null) } +
            unreadByApp(accounts, items, smsApp, dialer).map { (app, n) -> arrayOf<Any?>(ItemKind.NOTIFICATION.name, n, app) }
    }

    /** The items of these notifications (their keys), as stored: what HubEffects judges. Blocking. */
    fun notificationItems(keys: Set<String>): List<HubItem> =
        if (keys.isEmpty()) emptyList() else dao.conversationsNow(HubAccounts.NOTIFICATIONS.id, keys)

    /** Whether [key] is the key of a notification the Hub holds: the only ones it opens. Blocking. */
    fun holdsNotification(key: String): Boolean = dao.holdsNotificationNow(HubAccounts.NOTIFICATIONS.id, key)

    /** The rows of HubContract.CONVERSATION_URI: the notification [key]'s items, their texts whole. Blocking. */
    fun conversationRows(key: String): List<Array<Any?>> = db.runInTransaction<List<Array<Any?>>> {
        val color = dao.accountsNow().firstOrNull { it.id == HubAccounts.NOTIFICATIONS.id }?.color ?: AccountColor.NONE
        dao.conversationNow(HubAccounts.NOTIFICATIONS.id, key).map { itemRow(it, color, max = Int.MAX_VALUE) }
    }

    /** The rows of HubContract's items cursor, each item with its account's colour. Blocking, off the main thread. */
    fun itemRows(): List<Array<Any?>> = db.runInTransaction<List<Array<Any?>>> {
        val colors = dao.accountsNow().associate { it.id to it.color }
        dao.itemsNow().map { itemRow(it, colors[it.accountId] ?: AccountColor.NONE) }
    }
}
