package dev.bold7.essai

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Bundle

/**
 * Posts made-up notifications, as a third-party app would, in Android's rich styles, then goes
 * away: `su -c 'am start -n dev.bold7.essai/.NotifyActivity --es style messaging'`. Styles: text
 * (BigTextStyle, an accent colour and two actions), messaging (MessagingStyle with an inline
 * reply), picture (BigPictureStyle), media (MediaStyle), inbox (InboxStyle), progress (ongoing
 * progress bar), all (each of them), cancel (removes them all). Nothing is sent anywhere: the
 * actions and the reply only reopen this activity, which posts nothing without a style.
 * Channel "essai" at high importance, the one that asks Android for a heads-up card.
 */
class NotifyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Essai", NotificationManager.IMPORTANCE_HIGH))
        when (val style = intent.getStringExtra("style")) {
            null -> Unit
            "cancel" -> nm.cancelAll()
            "all" -> STYLES.forEachIndexed { i, s -> nm.notify(i + 1, build(s)) }
            else -> nm.notify(STYLES.indexOf(style).coerceAtLeast(0) + 1, build(style))
        }
        finish()
    }

    private fun build(style: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, NotifyActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(Color.rgb(0xE9, 0x1E, 0x63))
        return when (style) {
            "messaging" -> {
                val me = Person.Builder().setName("Me").build()
                val sender = Person.Builder().setName("Alice Martin").build()
                val reply = PendingIntent.getActivity(this, 1, Intent(this, NotifyActivity::class.java), PendingIntent.FLAG_MUTABLE)
                b.setStyle(Notification.MessagingStyle(me)
                    .addMessage("Are you coming tonight?", System.currentTimeMillis() - 60_000, sender)
                    .addMessage("Bring the charger please", System.currentTimeMillis(), sender))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .addAction(Notification.Action.Builder(null, "Reply", reply)
                        .addRemoteInput(RemoteInput.Builder("reply").setLabel("Reply").build()).build())
                    .addAction(Notification.Action.Builder(null, "Mark as read", open).build())
            }
            "picture" -> b.setContentTitle("Photo from Bob").setContentText("Holiday picture")
                .setStyle(Notification.BigPictureStyle().bigPicture(picture()))
            "media" -> {
                val session = MediaSession(this, "essai")
                b.setContentTitle("Spirit").setContentText("BlackBerry").setOngoing(false)
                    .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
                    .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_play), "Play", open).build())
            }
            "inbox" -> b.setContentTitle("3 new emails").setContentText("work@example.com")
                .setStyle(Notification.InboxStyle().addLine("Carol  Meeting moved").addLine("Dan  Invoice").addLine("Eve  Lunch?"))
                .setCategory(Notification.CATEGORY_EMAIL)
            "progress" -> b.setContentTitle("Downloading update").setProgress(100, 40, false).setOngoing(true).setAutoCancel(false)
            else -> b.setContentTitle("Essai Bold7").setContentText("A made-up notification of a third-party app, with a long text that goes on.")
                .setStyle(Notification.BigTextStyle().bigText("A made-up notification of a third-party app, with a long text that goes on over several lines."))
                .addAction(Notification.Action.Builder(null, "Open", open).build())
                .addAction(Notification.Action.Builder(null, "Dismiss", open).build())
        }.build()
    }

    private fun picture(): Bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawColor(Color.rgb(0x20, 0x80, 0xC0))
    }

    private companion object {
        const val CHANNEL = "essai"
        val STYLES = listOf("text", "messaging", "picture", "media", "inbox", "progress")
    }
}
