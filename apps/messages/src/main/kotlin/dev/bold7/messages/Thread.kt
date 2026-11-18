package dev.bold7.messages

import java.time.Instant
import java.time.format.DateTimeFormatter

// A conversation's messages as the thread of OS 7 shows them (docs/BBOS7-VISUEL.md
// §16), from Telephony's rows: SMS, MMS, and the RCS Google Messages copies into the MMS table
// (tr_id "proto:"). Pure; AndroidThreadReader reads the rows.

/** A row of content://sms of one thread; [type] Telephony.Sms.TYPE, [status] Telephony.Sms.STATUS. */
data class SmsRecord(val id: Long, val date: Long, val body: String, val type: Int, val status: Int, val read: Boolean, val address: String?)

/**
 * A row of content://mms of one thread, its text parts joined; [box] Telephony.Mms.MESSAGE_BOX;
 * [images] its image parts' ids, [others] its parts neither text, SMIL nor image, by id with their type.
 */
data class MmsRecord(
    val id: Long,
    val dateSeconds: Long,
    val box: Int,
    val read: Boolean,
    val text: String,
    val images: List<Long>,
    val others: List<Pair<Long, String>>,
    val address: String?,
)

/** An MMS part neither text, SMIL nor image ([uri] content://mms/part/<id>): a video, a sound, a card. */
data class MediaPart(val uri: String, val type: String)

/**
 * A bubble's state icon (net_rim_Message-icons32x32.png, docs/BBOS7-VISUEL.md §16); UNCONFIRMED: a
 * reply handed to another app that did not show it back (D).
 */
enum class Delivery { UNREAD, RECEIVED, SENDING, SENT, DELIVERED, FAILED, UNCONFIRMED }

data class ThreadMessage(
    /** Telephony's URI, what DeleteMessage takes. */
    val uri: String,
    val time: Long,
    /** Sent by the user: "Me" (sim-50, C). */
    val out: Boolean,
    val text: String,
    val delivery: Delivery,
    /** An MMS's images, as content://mms/part/<id>. */
    val images: List<String> = emptyList(),
    /** Its other parts: video, sound, vCard, iCalendar. */
    val media: List<MediaPart> = emptyList(),
    /** Who sent a received message: its address, for a group's bubbles. */
    val from: String? = null,
) {
    val otherParts: Int get() = media.size
}

/** Telephony.Sms.TYPE and STATUS to the bubble's icon; null for a draft, which the thread does not show. */
fun smsDelivery(type: Int, status: Int, read: Boolean): Delivery? = when (type) {
    1 -> if (read) Delivery.RECEIVED else Delivery.UNREAD
    // STATUS_COMPLETE 0, STATUS_PENDING 32, STATUS_FAILED 64 (a delivery report); NONE -1.
    2 -> when {
        status == 0 -> Delivery.DELIVERED
        status >= 64 -> Delivery.FAILED
        else -> Delivery.SENT
    }
    3 -> null
    5 -> Delivery.FAILED
    // Outbox 4, queued 6.
    else -> Delivery.SENDING
}

/** Telephony.Mms.MESSAGE_BOX to the bubble's icon; null for a draft. */
fun mmsDelivery(box: Int, read: Boolean): Delivery? = when (box) {
    1 -> if (read) Delivery.RECEIVED else Delivery.UNREAD
    2 -> Delivery.SENT
    3 -> null
    5 -> Delivery.FAILED
    // Outbox 4.
    else -> Delivery.SENDING
}

/** The thread, oldest first as OS 7 shows it (the newest above the field, sim-52, C); drafts left out. */
fun threadMessages(sms: List<SmsRecord>, mms: List<MmsRecord>): List<ThreadMessage> {
    val texts = sms.mapNotNull { r ->
        val d = smsDelivery(r.type, r.status, r.read) ?: return@mapNotNull null
        val out = r.type != 1
        ThreadMessage("content://sms/${r.id}", r.date, out, r.body, d, from = r.address.takeIf { !out })
    }
    val media = mms.mapNotNull { r ->
        val d = mmsDelivery(r.box, r.read) ?: return@mapNotNull null
        val out = r.box != 1
        ThreadMessage(
            "content://mms/${r.id}", r.dateSeconds * 1000, out, r.text, d,
            images = r.images.map { "content://mms/part/$it" }, media = r.others.map { (id, type) -> MediaPart("content://mms/part/$id", type) }, from = r.address.takeIf { !out },
        )
    }
    return (texts + media).sortedWith(compareBy<ThreadMessage> { it.time }.thenBy { it.uri })
}

/**
 * A bubble's time: the hour today (sim-50: "00:33", C), the day and the hour before (D: the 9900's
 * thread for an older message is not captured).
 */
fun bubbleTime(time: Long, now: Long, format: InboxFormat): String {
    val at = Instant.ofEpochMilli(time).atZone(format.zone)
    val hour = if (format.clock24) "HH:mm" else "h:mm a"
    val pattern = if (at.toLocalDate() == Instant.ofEpochMilli(now).atZone(format.zone).toLocalDate()) hour else "d MMM $hour"
    return DateTimeFormatter.ofPattern(pattern, format.locale).format(at)
}
