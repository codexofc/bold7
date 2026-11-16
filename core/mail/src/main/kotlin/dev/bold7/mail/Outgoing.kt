package dev.bold7.mail

import com.fsck.k9.mail.Address
import com.fsck.k9.mail.Message
import com.fsck.k9.mail.internet.AddressHeaderBuilder
import com.fsck.k9.mail.internet.MessageIdGenerator
import com.fsck.k9.mail.internet.MimeMessage
import com.fsck.k9.mail.internet.MimeMessageHelper
import com.fsck.k9.mail.internet.TextBody
import java.util.Date

/**
 * A mail written in Messages: plain text only, as the 9900 sent it. A reply names
 * the message it answers ([inReplyTo]) and its conversation ([references], oldest first), so
 * that the other side, and the Hub, thread it.
 */
data class OutgoingMail(
    val to: List<MailAddress>,
    val cc: List<MailAddress> = emptyList(),
    val subject: String,
    val text: String,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
)

private fun MailAddress.toAddress() = Address(email, name)

/** The MIME message [mail] makes, from [from], dated [date]: UTF-8 text in quoted-printable. */
fun OutgoingMail.toMessage(from: MailAddress, date: Date): MimeMessage {
    require(to.isNotEmpty() || cc.isNotEmpty()) { "No recipient" }
    val mail = this
    return MimeMessage().apply {
        setFrom(from.toAddress())
        if (mail.to.isNotEmpty()) setHeader("To", AddressHeaderBuilder.createHeaderValue(mail.to.map { it.toAddress() }.toTypedArray()))
        if (mail.cc.isNotEmpty()) setHeader("Cc", AddressHeaderBuilder.createHeaderValue(mail.cc.map { it.toAddress() }.toTypedArray()))
        subject = mail.subject
        setSentDate(date, false)
        setMessageId(MessageIdGenerator.getInstance().generateMessageId(this))
        mail.inReplyTo?.let(::setInReplyTo)
        if (mail.references.isNotEmpty()) setReferences(mail.references.joinToString(" "))
        MimeMessageHelper.setBody(this, TextBody(mail.text))
    }
}

/** A message as Messages opens it: its headers and its text. */
data class OpenedMail(
    val from: MailAddress?,
    val to: List<MailAddress>,
    val cc: List<MailAddress>,
    val replyTo: List<MailAddress>,
    val subject: String,
    /** The Date header, else the arrival; epoch ms. */
    val sentAt: Long,
    val messageId: String?,
    /** The References header's ids, oldest first. */
    val references: List<String>,
    /** The plain text part, else the HTML one as text ([htmlToText]). */
    val text: String,
)

private val MESSAGE_ID = Regex("<[^<>\\s]+>")

fun Message.toOpenedMail(body: MailBody): OpenedMail {
    fun list(type: Message.RecipientType) = getRecipients(type).orEmpty().map { MailAddress(it.personal?.takeIf { p -> p.isNotBlank() }, it.address) }
    return OpenedMail(
        from = from.orEmpty().firstOrNull()?.let { MailAddress(it.personal?.takeIf { p -> p.isNotBlank() }, it.address) },
        to = list(Message.RecipientType.TO),
        cc = list(Message.RecipientType.CC),
        replyTo = replyTo.orEmpty().map { MailAddress(it.personal?.takeIf { p -> p.isNotBlank() }, it.address) },
        subject = subject.orEmpty(),
        sentAt = (sentDate ?: internalDate)?.time ?: 0,
        messageId = messageId?.let { MESSAGE_ID.find(it)?.value },
        references = references.orEmpty().flatMap { r -> MESSAGE_ID.findAll(r).map { it.value } },
        text = body.text ?: body.html?.let(::htmlToText).orEmpty(),
    )
}
