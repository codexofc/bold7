package dev.bold7.mail

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.MessageExtractor
import com.fsck.k9.mail.internet.MimeUtility
import net.thunderbird.core.common.mail.Flag

data class MailAddress(val name: String?, val email: String)

/**
 * One message as the Hub needs it, built from headers, flags and the MIME structure only: no body
 * is downloaded for it. This is core/mail's output; the adapter to core/hub-data's item maps it.
 */
data class MailItem(
    val account: String,
    val folder: String,
    val role: FolderRole,
    val uid: Long,
    val messageId: String?,
    /** Same key for every message of a conversation ([threadKey]). */
    val thread: String?,
    val from: MailAddress?,
    val to: List<MailAddress>,
    val subject: String,
    /** Arrival on the server (IMAP INTERNALDATE), else the Date header; epoch milliseconds. */
    val receivedAt: Long,
    val flags: MailFlags,
    val hasAttachment: Boolean,
)

data class MailBody(val text: String?, val html: String?)

fun Message.toMailItem(account: String, folder: SyncedFolder, uid: Long) = MailItem(
    account = account,
    folder = folder.serverId,
    role = folder.role,
    uid = uid,
    messageId = messageId?.trim(),
    thread = threadKey(),
    from = from.firstOrNull()?.toMailAddress(),
    to = getRecipients(Message.RecipientType.TO).map { it.toMailAddress() },
    subject = subject.orEmpty(),
    receivedAt = (internalDate ?: sentDate)?.time ?: 0,
    flags = flags(),
    hasAttachment = hasAttachment(),
)

fun Message.flags() = MailFlags(read = isSet(Flag.SEEN), flagged = isSet(Flag.FLAGGED), answered = isSet(Flag.ANSWERED))

private fun Address.toMailAddress() = MailAddress(personal?.takeIf { it.isNotBlank() }, address)

private val MESSAGE_ID = Regex("<[^<>\\s]+>")

/** The conversation's first message: first of References, else In-Reply-To, else this message. */
fun Message.threadKey(): String? =
    references.orEmpty().firstNotNullOfOrNull { MESSAGE_ID.find(it)?.value }
        ?: getHeader("In-Reply-To").firstNotNullOfOrNull { MESSAGE_ID.find(it)?.value }
        ?: messageId?.let { MESSAGE_ID.find(it)?.value }

/** A part sent as an attachment; an image shown inside the HTML (disposition inline) is not one. */
fun Part.hasAttachment(): Boolean {
    val body = body
    if (body is Multipart) return body.bodyParts.any { it.hasAttachment() }
    return when (disposition?.let { MimeUtility.getHeaderParameter(it, null) }?.lowercase()) {
        "attachment" -> true
        "inline" -> false
        else -> !MimeUtility.mimeTypeMatches(mimeType, "text/*") &&
            MimeUtility.getHeaderParameter(contentType, "name") != null
    }
}

/** First plain text part and first HTML part outside attachments: what [Mailbox.body] downloads. */
fun Part.readableParts(): List<Part> {
    var text: Part? = null
    var html: Part? = null
    fun visit(part: Part) {
        val body = part.body
        when {
            body is Multipart -> body.bodyParts.forEach(::visit)
            part.hasAttachment() -> Unit
            text == null && part.isMimeType("text/plain") -> text = part
            html == null && part.isMimeType("text/html") -> html = part
        }
    }
    visit(this)
    return listOfNotNull(text, html)
}

fun Part.readableBody(): MailBody {
    val parts = readableParts()
    fun textOf(mimeType: String) = parts.firstOrNull { it.isMimeType(mimeType) }?.let(MessageExtractor::getTextFromPart)
    return MailBody(text = textOf("text/plain"), html = textOf("text/html"))
}
