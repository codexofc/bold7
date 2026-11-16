package dev.bold7.hub.data

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * The Hub for other processes (HubContract), read only. Declared by apps/hub, whose process owns
 * the database; the system checks the permissions of a query before calling. Answers on a binder
 * thread, the database opening at the first query.
 */
open class HubProvider : ContentProvider() {
    override fun onCreate() = true

    private fun store() = HubStore(HubDatabase.get(context!!), System::currentTimeMillis) {}

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        when (uri.toString()) {
            HubContract.COUNTS_URI -> MatrixCursor(arrayOf(HubContract.ACCOUNT, HubContract.KIND, HubContract.UNREAD, HubContract.NEW, HubContract.SEEN)).apply {
                // The last visits for Bold7's apps only: SystemUI (STATUS_BAR) gets the counts.
                val seen = context!!.checkCallingPermission(HubContract.PERMISSION) == PackageManager.PERMISSION_GRANTED
                store().counterRows().forEach { row -> addRow(if (seen) row else row.copyOf().also { it[4] = null }) }
            }
            // The roles as they stand now: the launcher reads the same two for its own count.
            HubContract.KINDS_URI -> MatrixCursor(arrayOf(HubContract.KIND, HubContract.UNREAD)).apply {
                val context = context!!
                val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
                store().kindRows(Telephony.Sms.getDefaultSmsPackage(context), dialer).forEach(::addRow)
            }
            HubContract.ITEMS_URI -> MatrixCursor(HubContract.ITEM_COLUMNS, 256).apply { store().itemRows().forEach(::addRow) }
            else -> throw IllegalArgumentException("Unknown Hub URI")
        }

    /**
     * The live contentIntent of the notification whose key is [key], for the caller to send, or
     * null: gone, without one, or no listener bound. The process that holds the notifications
     * (apps/hub) answers. Called only for a key the Hub holds, the phone unlocked.
     */
    protected open fun open(key: String): PendingIntent? = null

    /** Notifications the user read in the Hub: their own "mark as read" action, where they have one. */
    protected open fun markedRead(keys: Set<String>) = Unit

    /** Notifications the user deleted in the Hub: gone from Android too. */
    protected open fun deleted(keys: Set<String>) = Unit

    /** A write the user asked ([HubCall.Write]), done in the store: apps/hub carries a mail's to its server. */
    protected open fun written(method: String, keys: List<ItemKey>) = Unit

    // ContentProvider.call checks no permission by itself: READ_HUB here, never STATUS_BAR, and
    // an intent leaves, or the store changes, only for the signature holders. Only a notification
    // of the Hub's list (not an ongoing one, a system one or Bold7's own), and nothing while
    // locked (CONTRIBUTING.md): the Hub's actions included.
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context!!
        if (context.checkCallingPermission(HubContract.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("${HubContract.PERMISSION} required")
        }
        val result = Bundle()
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val call = hubCall(
            method, arg, extras?.getStringArray(HubContract.EXTRA_ACCOUNTS), extras?.getStringArray(HubContract.EXTRA_KEYS), locked,
            extras?.getLongArray(HubContract.EXTRA_SEEN_UP_TO),
        )
        when (call) {
            HubCall.Refused -> Unit
            // A store that announces its writes: the launcher's splats read them back.
            is HubCall.Seen -> hubStore(context).markSeen(call.upTo)
            is HubCall.Open -> if (store().holdsNotification(call.key)) open(call.key)?.let { result.putParcelable(HubContract.EXTRA_INTENT, it) }
            is HubCall.Write -> {
                // A store that announces its writes: the launcher's list reads them back.
                val store = hubStore(context)
                val changed = when (call.method) {
                    HubContract.METHOD_MARK_READ -> store.setRead(call.keys, read = true)
                    HubContract.METHOD_MARK_UNREAD -> store.setRead(call.keys, read = false)
                    else -> store.delete(call.keys)
                }
                // Even when the store had it so already: the server may not (a write that failed offline).
                written(call.method, call.keys)
                if (changed > 0) {
                    // Only the notifications the Hub holds, each as a whole (hubEffects).
                    val effects = hubEffects(call, store.notificationItems(notificationKeys(call.keys)))
                    if (effects.markRead.isNotEmpty()) markedRead(effects.markRead)
                    if (effects.cancel.isNotEmpty()) deleted(effects.cancel)
                }
            }
        }
        return result
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
