package dev.bold7.hub

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.HubProvider
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.MailContract
import dev.bold7.hub.data.MailResult

/**
 * HubProvider with what only this process has: the notifications its listener holds, to open one
 * from the launcher's Hub (HubContract.METHOD_OPEN), to read one through its own action, or to
 * cancel one deleted from the Hub.
 */
class Provider : HubProvider() {
    // A read of the Hub is the only sign this process gets that a permission may have come.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        (context!!.applicationContext as HubApp).graphOrNull?.retryRefused()
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }

    // Mail (MailContract): READ_HUB checked here as HubProvider.call does, nothing
    // while locked (CONTRIBUTING.md), then MailHost on this binder thread (it may wait on a server).
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!method.startsWith("mail")) return super.call(method, arg, extras)
        val context = context!!
        if (context.checkCallingPermission(HubContract.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("${HubContract.PERMISSION} required")
        }
        val graph = (context.applicationContext as HubApp).graphOrNull
        if (graph == null || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            return Bundle().apply { putString(MailContract.EXTRA_RESULT, MailResult.REFUSED.name) }
        }
        return graph.mail.call(method, arg, extras)
    }

    override fun written(method: String, keys: List<ItemKey>) {
        (context!!.applicationContext as HubApp).graphOrNull?.mail?.written(method, keys)
    }

    // A binder thread of this process: getActiveNotifications is a binder call to
    // NotificationManagerService, allowed to the bound listener only. The notification stays:
    // Android's shade closes an auto-cancel one on a tap (REASON_CLICK), a listener could only
    // cancel it (REASON_LISTENER_CANCEL), which sends the app its deleteIntent as if dismissed.
    override fun open(key: String): PendingIntent? =
        HubListener.live?.getActiveNotifications(arrayOf(key))?.firstOrNull()?.notification?.contentIntent

    // The app's own "Mark as read" (Google Messages, Gmail, …): it reads the message in its app,
    // which then removes the notification itself. Without one, the Hub alone holds it read.
    override fun markedRead(keys: Set<String>) {
        val live = HubListener.live ?: return
        for (sbn in live.getActiveNotifications(keys.toTypedArray()).orEmpty()) {
            // Not one that asks for a text (a RemoteInput): it would be sent empty.
            val action = sbn.notification.actions?.firstOrNull {
                it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ && it.remoteInputs.isNullOrEmpty()
            } ?: continue
            try {
                action.actionIntent?.send()
            } catch (e: PendingIntent.CanceledException) {
                Log.w(TAG, "mark as read: the app cancelled its action")
            }
        }
    }

    // D7: deleted from the Hub, gone from Android as if dismissed (the app gets its deleteIntent).
    // The listener's removal callback then marks the items read, as for any removal.
    override fun deleted(keys: Set<String>) {
        HubListener.live?.cancelNotifications(keys.toTypedArray())
    }

    private companion object {
        const val TAG = "Bold7/Hub"
    }
}
