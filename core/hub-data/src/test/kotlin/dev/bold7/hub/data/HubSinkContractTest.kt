package dev.bold7.hub.data

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What a source can count on when it writes (HubSink), checked on the Room store with its real
 * queries and on the fake the sources' tests use, so both behave the same.
 */
abstract class HubSinkContract {
    protected abstract val sink: HubSink
    protected abstract suspend fun accounts(): List<Account>
    protected abstract suspend fun items(): List<HubItem>
    protected abstract suspend fun attachments(accountId: String, sourceKey: String): List<Attachment>
    protected abstract fun changes(): Int

    /** The store's clock: what it stamps as an item's arrival. */
    protected var clock = 0L

    private val sms = Account("sms", AccountKind.TEXT_MESSAGES, "Text Messages")
    private fun item(key: String, time: Long = 1, read: Boolean = false) =
        HubItem("sms", key, ItemKind.SMS, time, "Alice", preview = "p$key", read = read)

    @Test fun aSourceRewritesItsItemsWithoutDuplicates() = runTest {
        sink.putAccount(sms)
        sink.put(listOf(item("1"), item("2")))
        sink.put(listOf(item("1", read = true)))
        assertEquals(setOf(item("1", read = true), item("2")), items().toSet())
    }

    @Test fun anItemKeepsItsFirstArrivalWhenRewritten() = runTest {
        sink.putAccount(sms)
        clock = 100
        sink.put(listOf(item("1")))
        clock = 200
        sink.put(listOf(item("1", read = true), item("2")))
        assertEquals(setOf(item("1", read = true).copy(addedAt = 100), item("2").copy(addedAt = 200)), items().toSet())
    }

    @Test fun puttingAnAccountAgainKeepsWhatTheHubOwns() = runTest {
        sink.putAccount(sms)
        sink.putAccount(sms.copy(name = "SMS", seenAt = 0))
        assertEquals(listOf(sms.copy(name = "SMS")), accounts())
        // What the Hub set (order, colour, last visit) survives a source starting again.
        markSeenAndColour()
        sink.putAccount(sms.copy(name = "Texts", address = "+33"))
        assertEquals(listOf(sms.copy(name = "Texts", address = "+33", color = AccountColor.GREEN, position = 3, seenAt = 42)), accounts())
    }

    protected abstract suspend fun markSeenAndColour()

    @Test fun attachmentsAreReplacedPerItemAndSurviveTheItemsUpdate() = runTest {
        sink.putAccount(sms)
        sink.put(listOf(item("1")))
        val photo = Attachment("sms", "1", 0, "photo.jpg", "image/jpeg", 1000)
        sink.putAttachments("sms", "1", listOf(photo, photo.copy(position = 1, name = "b.jpg")))
        sink.putAttachments("sms", "1", listOf(photo))
        sink.put(listOf(item("1", read = true)))
        assertEquals(listOf(photo), attachments("sms", "1"))
    }

    @Test fun removingAnItemRemovesItsAttachments() = runTest {
        sink.putAccount(sms)
        sink.put(listOf(item("1"), item("2")))
        sink.putAttachments("sms", "1", listOf(Attachment("sms", "1", 0, "a", "text/plain")))
        sink.remove("sms", listOf("1", "unknown"))
        assertEquals(listOf("2"), items().map { it.sourceKey })
        assertEquals(emptyList(), attachments("sms", "1"))
    }

    @Test fun replaceRemovesThenPutsAndIsAnnouncedOnce() = runTest {
        sink.putAccount(sms)
        clock = 100
        sink.put(listOf(item("1"), item("2")))
        val before = changes()
        clock = 200
        sink.replace("sms", listOf("2", "unknown"), listOf(item("1", read = true), item("3")))
        assertEquals(setOf(item("1", read = true).copy(addedAt = 100), item("3").copy(addedAt = 200)), items().toSet())
        assertEquals(before + 1, changes())
    }

    @Test fun removingAnAccountRemovesItsItemsOnly() = runTest {
        sink.putAccount(sms)
        sink.putAccount(Account("mail-1", AccountKind.EMAIL, "a@b.c", address = "a@b.c"))
        sink.put(listOf(item("1"), HubItem("mail-1", "INBOX|1|7", ItemKind.EMAIL, 1, "Bob")))
        sink.putAttachments("mail-1", "INBOX|1|7", listOf(Attachment("mail-1", "INBOX|1|7", 0, "a", "text/plain")))
        sink.removeAccount("mail-1")
        assertEquals(listOf("sms"), accounts().map { it.id })
        assertEquals(listOf("1"), items().map { it.sourceKey })
        assertEquals(emptyList(), attachments("mail-1", "INBOX|1|7"))
    }

    @Test fun aSourceReadsBackTheItemsOfOneAccountWithTheirArrival() = runTest {
        sink.putAccount(sms)
        sink.putAccount(Account("calls", AccountKind.CALLS, "Calls"))
        clock = 7
        sink.put(listOf(item("1"), HubItem("calls", "1", ItemKind.MISSED_CALL, 1, "Bob")))
        assertEquals(listOf(item("1").copy(addedAt = 7)), sink.items("sms"))
        assertEquals(emptyList(), sink.items("unknown"))
    }

    @Test fun markingReadCountsAndAnnouncesOnlyWhatWasUnread() = runTest {
        sink.putAccount(sms)
        sink.putAccount(Account("calls", AccountKind.CALLS, "Calls"))
        sink.put(
            listOf(
                item("1").copy(conversationId = "a"),
                item("2", read = true).copy(conversationId = "a", sourceRead = true),
                item("3").copy(conversationId = "b"),
                item("4"),
                HubItem("calls", "1", ItemKind.MISSED_CALL, 1, "Bob", conversationId = "a"),
            ),
        )
        val changes = changes()
        assertEquals(1, sink.markRead("sms", listOf("a", "unknown")))
        assertEquals(changes + 1, changes())
        // Nothing unread left there: nothing written, nothing announced.
        assertEquals(0, sink.markRead("sms", listOf("a")))
        assertEquals(changes + 1, changes())
        assertEquals(
            mapOf("sms/1" to true, "sms/2" to true, "sms/3" to false, "sms/4" to false, "calls/1" to false),
            items().associate { "${it.accountId}/${it.sourceKey}" to it.read },
        )
    }

    @Test fun aSourceReadsBackOneItem() = runTest {
        sink.putAccount(sms)
        clock = 3
        sink.put(listOf(item("1")))
        assertEquals(item("1").copy(addedAt = 3), sink.item("sms", "1"))
        assertNull(sink.item("sms", "2"))
        assertNull(sink.item("calls", "1"))
    }

    @Test fun theSourcesReadIsTheHubsReadToo() = runTest {
        // A notification removed: read for its source and for the Hub, even if the Hub had read it.
        sink.putAccount(sms)
        sink.put(listOf(item("1", read = true).copy(conversationId = "a")))
        assertEquals(1, sink.markRead("sms", listOf("a")))
        assertEquals(listOf(true to true), items().map { it.read to it.sourceRead })
    }

    @Test fun everyWriteIsAnnounced() = runTest {
        sink.putAccount(sms)
        sink.put(listOf(item("1")))
        sink.putAttachments("sms", "1", emptyList())
        sink.remove("sms", listOf("1"))
        assertEquals(4, changes())
    }
}

class FakeHubSinkTest : HubSinkContract() {
    private val fake = FakeHubSink { clock }
    override val sink: HubSink get() = fake
    override suspend fun accounts() = fake.accounts.values.toList()
    override suspend fun items() = fake.items.values.toList()
    override suspend fun attachments(accountId: String, sourceKey: String) = fake.attachments[accountId to sourceKey].orEmpty()
    override fun changes() = fake.changes
    override suspend fun markSeenAndColour() {
        fake.accounts["sms"] = fake.accounts.getValue("sms").copy(color = AccountColor.GREEN, position = 3, seenAt = 42)
    }
}

class HubStoreTest : HubSinkContract() {
    // Room's Android builder only keeps the context for files: an in-memory database never asks it.
    private val db = Room.inMemoryDatabaseBuilder(ContextWrapper(null), HubDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        // Neither a main looper nor ActivityManager on the JVM to ask.
        .allowMainThreadQueries()
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .build()
    private var changed = 0
    private val store = HubStore(db, now = { clock }) { changed++ }

    override val sink: HubSink get() = store
    override suspend fun accounts() = store.accounts()
    override suspend fun items() = store.items()
    override suspend fun attachments(accountId: String, sourceKey: String) = store.attachments(accountId, sourceKey)
    override fun changes() = changed
    override suspend fun markSeenAndColour() {
        store.markSeen("sms", 42)
        store.setLook("sms", AccountColor.GREEN, 3)
    }

    @AfterTest fun close() = db.close()

    @Test fun theDatabaseRefusesDeviceProtectedStorage() {
        val deviceProtected = object : ContextWrapper(null) {
            override fun getApplicationContext() = this
            override fun isDeviceProtectedStorage() = true
        }
        assertFailsWith<IllegalStateException> { HubDatabase.get(deviceProtected) }
    }

    @Test fun theCountersQueryAgreesWithThePureCounters() = runTest {
        val accounts = listOf(
            Account("sms", AccountKind.TEXT_MESSAGES, "Text Messages", position = 1),
            Account("mail", AccountKind.EMAIL, "Mail", position = 0),
        )
        val items = listOf(
            HubItem("sms", "1", ItemKind.SMS, 10, "a"),
            HubItem("sms", "2", ItemKind.SMS, 50, "a", read = true),
            HubItem("mail", "1", ItemKind.EMAIL, 60, "b"),
            HubItem("mail", "2", ItemKind.EMAIL, 70, "b", box = Box.DRAFT),
            HubItem("mail", "3", ItemKind.EMAIL, 80, "b", box = Box.SENT, read = false),
        )
        accounts.forEach { store.putAccount(it) }
        // The mail arrives before the mail account's visit, the texts after: only the texts are new.
        clock = 60
        store.put(items.filter { it.accountId == "mail" })
        store.markSeen("mail", 65)
        clock = 70
        store.put(items.filter { it.accountId == "sms" })
        val expected = counts(store.accounts(), store.items())
        assertEquals(Count(unread = 2, new = 1), expected.total)
        assertEquals(
            listOf(
                listOf(null, null, 2, 1, null),
                listOf("mail", "EMAIL", 1, 0, 65L),
                listOf("sms", "TEXT_MESSAGES", 1, 1, 0L),
            ),
            store.counterRows().map { it.toList() },
        )
    }

    @Test fun aVisitMarksItsAccountsSeenUpToWhatWasShownAndAnnouncesOnlyAChange() = runTest {
        store.putAccount(Account("sms", AccountKind.TEXT_MESSAGES, "Text Messages"))
        store.putAccount(Account("calls", AccountKind.CALLS, "Calls", position = 1))
        clock = 10
        store.put(listOf(HubItem("sms", "1", ItemKind.SMS, 1, "a"), HubItem("calls", "1", ItemKind.MISSED_CALL, 1, "a")))
        assertEquals(listOf<Any?>(1, 1), store.counterRows().drop(1).map { it[3] })
        val changes = changed
        assertEquals(1, store.markSeen(mapOf("sms" to 10L, "gone" to 10L)))
        assertEquals(changes + 1, changed)
        // Seen again up to the same arrival: nothing changes, nothing is announced.
        assertEquals(0, store.markSeen(mapOf("sms" to 10L)))
        assertEquals(changes + 1, changed)
        assertEquals(listOf<Any?>(0, 1), store.counterRows().drop(1).map { it[3] })
        // A visit never moves an account's last visit back.
        assertEquals(0, store.markSeen(mapOf("sms" to 5L)))
        assertEquals(10L, store.accounts().first { it.id == "sms" }.seenAt)
    }

    @Test fun anItemStampedAheadOfTheClockIsSeenOnceItsArrivalIsSent() = runTest {
        store.putAccount(Account("sms", AccountKind.TEXT_MESSAGES, "Text Messages"))
        clock = 1_000
        store.put(listOf(HubItem("sms", "1", ItemKind.SMS, 1, "a")))
        // The clock goes back: the visit says up to the item's own arrival, not now.
        clock = 10
        assertEquals(1, store.markSeen(mapOf("sms" to 1_000L)))
        assertEquals(0, store.counterRows().drop(1).single()[3])
        assertEquals(0, store.markSeen(mapOf("sms" to 1_000L)))
    }

    @Test fun onlyANotificationTheHubHoldsCanBeOpened() = runTest {
        store.putAccount(HubAccounts.NOTIFICATIONS)
        store.putAccount(HubAccounts.TEXT_MESSAGES)
        store.put(
            listOf(
                HubItem("notifications", "k@1", ItemKind.NOTIFICATION, 1, "a", conversationId = "k"),
                HubItem("sms", "sms:1", ItemKind.SMS, 1, "a", conversationId = "t"),
            ),
        )
        assertEquals(true, store.holdsNotification("k"))
        assertEquals(false, store.holdsNotification("t")) // a thread id, not a notification's key
        assertEquals(false, store.holdsNotification("0|android|1|null|1000"))
    }

    private fun key(account: String, key: String) = ItemKey(account, key)

    @Test fun theHubMarksItemsReadOrUnreadAndAnnouncesOnlyAChange() = runTest {
        store.putAccount(Account("sms", AccountKind.TEXT_MESSAGES, "Text Messages"))
        store.put(listOf(HubItem("sms", "1", ItemKind.SMS, 1, "a"), HubItem("sms", "2", ItemKind.SMS, 2, "a", read = true)))
        val changes = changed
        assertEquals(1, store.setRead(listOf(key("sms", "1"), key("sms", "2"), key("sms", "gone"), key("calls", "1")), read = true))
        assertEquals(changes + 1, changed)
        assertEquals(0, store.setRead(listOf(key("sms", "1")), read = true))
        assertEquals(changes + 1, changed)
        assertEquals(2, store.setRead(listOf(key("sms", "1"), key("sms", "2")), read = false))
        // The source's own state stays as it was: its next change of mind still counts.
        assertEquals(listOf(false to false, false to false), store.items().sortedBy { it.sourceKey }.map { it.read to it.sourceRead })
    }

    @Test fun aDeletedItemLeavesTheCountersButStaysInTheItemsCursor() = runTest {
        store.putAccount(Account("sms", AccountKind.TEXT_MESSAGES, "Text Messages"))
        store.put(listOf(HubItem("sms", "1", ItemKind.SMS, 1, "a"), HubItem("sms", "2", ItemKind.SMS, 2, "a")))
        val changes = changed
        assertEquals(1, store.delete(listOf(key("sms", "1"), key("sms", "gone"))))
        assertEquals(changes + 1, changed)
        assertEquals(0, store.delete(listOf(key("sms", "1"))))
        assertEquals(changes + 1, changed)
        assertEquals(1, store.counterRows().first()[2])
        // Kept as a mark: its source reads it again without bringing it back, and the list sees it
        // to leave out the notification that repeats it (withoutEchoes).
        val rows = store.itemRows().map { row -> itemFromRow { i -> row[i] }.first }
        assertEquals(mapOf("1" to true, "2" to false), rows.associate { it.sourceKey to it.deleted })
    }

    @Test fun theItemsOfNamedNotificationsAreReadBackForTheProvider() = runTest {
        store.putAccount(HubAccounts.NOTIFICATIONS)
        store.putAccount(HubAccounts.TEXT_MESSAGES)
        val k1 = HubItem("notifications", "K@1", ItemKind.NOTIFICATION, 1, "a", conversationId = "K")
        val k2 = k1.copy(sourceKey = "K@2")
        store.put(listOf(k1, k2, k1.copy(sourceKey = "J@1", conversationId = "J"), HubItem("sms", "sms:1", ItemKind.SMS, 1, "a", conversationId = "K")))
        assertEquals(setOf("K@1", "K@2"), store.notificationItems(setOf("K", "X")).map { it.sourceKey }.toSet())
        assertEquals(emptyList(), store.notificationItems(emptySet()))
    }

    @Test fun aVersion1DatabaseOpensWithItsItemsKept() = runTest {
        val file = File.createTempFile("hub", ".db").apply { delete() }
        try {
            // Version 1 as the phone holds it: the statements of its committed schema.
            val v1 = schemaStatements(File("schemas/dev.bold7.hub.data.HubDatabase/1.json")) + listOf(
                "PRAGMA user_version = 1",
                "INSERT INTO account VALUES ('sms', 'TEXT_MESSAGES', 'Text Messages', NULL, 'NONE', 1, 0)",
                "INSERT INTO item VALUES ('sms', 'sms:1', 'SMS', 5, 'Alice', NULL, NULL, 'Hi', 'INBOX', NULL, '7', 1, 0, 'NORMAL', 0, 0, 9)",
            )
            BundledSQLiteDriver().open(file.path).use { db -> v1.forEach { sql -> db.prepare(sql).use { it.step() } } }
            val context = object : ContextWrapper(null) {
                override fun getDatabasePath(name: String) = File(name)
                override fun getApplicationContext() = this
            }
            val v2 = Room.databaseBuilder(context, HubDatabase::class.java, file.path)
                .setDriver(BundledSQLiteDriver())
                .allowMainThreadQueries()
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build()
            try {
                // Version 1 held the source's state: it is the Hub's and the source's alike.
                assertEquals(
                    listOf(HubItem("sms", "sms:1", ItemKind.SMS, 5, "Alice", preview = "Hi", conversationId = "7", read = true, addedAt = 9, sourceRead = true)),
                    HubStore(v2, { 0 }) {}.items(),
                )
            } finally {
                v2.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test fun aVersion2DatabaseOpensWithItsItemsKeptAndTheirCategoryUnknown() = runTest {
        val file = File.createTempFile("hub", ".db").apply { delete() }
        try {
            val v2 = schemaStatements(File("schemas/dev.bold7.hub.data.HubDatabase/2.json")) + listOf(
                "PRAGMA user_version = 2",
                "INSERT INTO account VALUES ('notifications', 'NOTIFICATIONS', 'Notifications', NULL, 'NONE', 0, 0)",
                "INSERT INTO item VALUES ('notifications', 'k@1', 'NOTIFICATION', 5, 'Alice', NULL, NULL, 'Hi', 'INBOX', 'com.whatsapp', 'k', 0, 0, 'NORMAL', 0, 0, 9, 0, 1)",
                "INSERT INTO item VALUES ('notifications', 'k@2', 'NOTIFICATION', 6, 'Bob', NULL, NULL, 'Yo', 'INBOX', 'com.app', 'j', 1, 0, 'NORMAL', 0, 0, 10, 1, 0)",
            )
            BundledSQLiteDriver().open(file.path).use { db -> v2.forEach { sql -> db.prepare(sql).use { it.step() } } }
            val context = object : ContextWrapper(null) {
                override fun getDatabasePath(name: String) = File(name)
                override fun getApplicationContext() = this
            }
            val v3 = Room.databaseBuilder(context, HubDatabase::class.java, file.path)
                .setDriver(BundledSQLiteDriver())
                .allowMainThreadQueries()
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build()
            try {
                // Nothing lost; the category is unknown (null), not "none" (""), so the package's rule still holds.
                assertEquals(
                    listOf(
                        HubItem("notifications", "k@1", ItemKind.NOTIFICATION, 5, "Alice", preview = "Hi", folder = "com.whatsapp", conversationId = "k", addedAt = 9, deleted = true),
                        HubItem("notifications", "k@2", ItemKind.NOTIFICATION, 6, "Bob", preview = "Yo", folder = "com.app", conversationId = "j", read = true, addedAt = 10, sourceRead = true),
                    ),
                    HubStore(v3, { 0 }) {}.items().sortedBy { it.sourceKey },
                )
            } finally {
                v3.close()
            }
        } finally {
            file.delete()
        }
    }

    /** The CREATE statements and setup queries of an exported Room schema, read without a JSON library. */
    private fun schemaStatements(schema: File): List<String> {
        val tableName = Regex("^\"tableName\": \"(\\w+)\"")
        val createSql = Regex("^\"createSql\": \"(.*)\",?$")
        var table = ""
        var setup = false
        val sql = mutableListOf<String>()
        for (line in schema.readLines().map { it.trim() }) {
            tableName.find(line)?.let { table = it.groupValues[1] }
            createSql.find(line)?.let { sql += it.groupValues[1].replace("\${TABLE_NAME}", table) }
            if (line.startsWith("\"setupQueries\"")) setup = true else if (setup && line.startsWith("\"")) sql += line.trimEnd(',').removeSurrounding("\"")
        }
        return sql
    }

    @Test fun theItemsQueryCarriesEachItemWithItsArrivalAndItsAccountColour() = runTest {
        store.putAccount(Account("sms", AccountKind.TEXT_MESSAGES, "Text Messages"))
        store.putAccount(Account("calls", AccountKind.CALLS, "Calls"))
        store.setLook("sms", AccountColor.ORANGE, 1)
        clock = 33
        val text = HubItem("sms", "1", ItemKind.SMS, 10, "a", preview = "hi")
        val call = HubItem("calls", "2", ItemKind.MISSED_CALL, 20, "b")
        store.put(listOf(text, call))
        val read = store.itemRows().map { row -> itemFromRow { i -> row[i] } }.toSet()
        assertEquals(setOf(text.copy(addedAt = 33) to AccountColor.ORANGE, call.copy(addedAt = 33) to AccountColor.NONE), read)
    }
}
