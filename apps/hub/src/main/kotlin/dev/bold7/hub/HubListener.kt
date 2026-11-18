package dev.bold7.hub

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.bold7.hub.data.Posted
import dev.bold7.hub.data.PostedMessage
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * The notifications of every app, bound by system_server once
 * tools/install.sh allows it. Callbacks come on the main thread: it copies and hands over, the
 * sources' thread writes. Its dump is the Hub's diagnostic.
 */
class HubListener : NotificationListenerService() {
    private val graph get() = (application as HubApp).graph

    // Once unbound, getActiveNotifications() still asks NotificationManagerService (mWrapper is
    // never cleared on the Q25), which most likely refuses: a failure HubGraph counts. Only a
    // RemoteException, system_server dying, answers an empty array; then nothing is marked read.
    @Volatile private var bound = false

    companion object {
        /** The listener while the system binds it, for the provider to open a notification (Provider). */
        @Volatile var live: HubListener? = null
            private set
    }

    private fun sinceNow(): () -> Long {
        val seen = SystemClock.elapsedRealtime()
        return { SystemClock.elapsedRealtime() - seen }
    }

    override fun onListenerConnected() {
        bound = true
        live = this
        graph.diagnostics.listenerConnected()
        // The snapshot is a binder call: on the sources' thread too. Unbound again by then, it
        // proves nothing removed.
        graph.run("notifications", sinceNow()) {
            val active = activeNotifications.orEmpty().map(::copy)
            connected(active, complete = bound)
        }
    }

    override fun onListenerDisconnected() {
        bound = false
        if (live === this) live = null
        graph.diagnostics.listenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val posted = copy(sbn)
        graph.run("notifications", { System.currentTimeMillis() - posted.postTime }) { posted(posted) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key
        graph.run("notifications", sinceNow()) { removed(key) }
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        graph.diagnostics.dump(writer)
        graph.mail.dump(writer)
    }
}

/**
 * MessagingStyle's messages are bundles whose keys ("text", "time", "sender", "sender_person") the
 * platform reads itself (Notification.MessagingStyle.Message.getMessagesFromBundleArray, hidden);
 * one without text is dropped, as the platform drops it.
 */
@Suppress("DEPRECATION") // The untyped getters: the array is a Parcelable[], not a Bundle[].
private fun copy(sbn: StatusBarNotification): Posted {
    val n = sbn.notification
    val x = n.extras
    val title = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
    val messages = x.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty().mapNotNull { it as? Bundle }.mapNotNull { m ->
        val text = m.getCharSequence("text")?.toString() ?: return@mapNotNull null
        val person = m.getParcelable("sender_person") as? Person
        val name = (person?.name ?: m.getCharSequence("sender"))?.toString()
        PostedMessage(text, m.getLong("time"), name, person?.key ?: name)
    }
    val user = x.getParcelable(Notification.EXTRA_MESSAGING_PERSON) as? Person
    return Posted(
        key = sbn.key,
        packageName = sbn.packageName,
        postTime = sbn.postTime,
        whenTime = n.`when`,
        flags = n.flags,
        // Notification.MediaStyle and DecoratedMediaCustomViewStyle, also behind NotificationCompat.
        media = x.getString(Notification.EXTRA_TEMPLATE)?.contains("Media") == true,
        title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        bigText = x.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        conversationTitle = title,
        messages = messages,
        userKey = user?.key ?: user?.name?.toString(),
        // Android's own fallback for an app that does not say: a conversation title makes a group.
        group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, title != null),
        category = n.category,
        messaging = x.getString(Notification.EXTRA_TEMPLATE)?.contains("Messaging") == true,
    )
}
