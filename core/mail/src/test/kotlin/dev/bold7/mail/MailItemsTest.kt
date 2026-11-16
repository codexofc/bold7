package dev.bold7.mail

import com.fsck.k9.mail.internet.BinaryTempFileBody
import com.fsck.k9.mail.internet.MimeMessage
import java.nio.file.Files
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.thunderbird.core.common.mail.Flag

class MailItemsTest {
    init {
        BinaryTempFileBody.setTempDirectory(Files.createTempDirectory("mime").toFile())
    }

    private fun parse(raw: String) = MimeMessage.parseMimeMessage(raw.trimIndent().replace("\n", "\r\n").byteInputStream(), true)

    private val plain = """
        From: =?UTF-8?Q?Ren=C3=A9e?= <renee@example.org>
        To: Alice <alice@gmail.com>, bob@example.org
        Subject: =?UTF-8?Q?R=C3=A9union?= de lundi
        Date: Mon, 9 Nov 2026 10:00:00 +0100
        Message-ID: <c@example.org>
        In-Reply-To: <b@example.org>
        References: <a@example.org> <b@example.org>
        Content-Type: text/plain; charset=utf-8

        Bonjour
    """

    @Test
    fun `headers become a Hub item`() {
        val message = parse(plain).apply {
            setFlag(Flag.SEEN, true)
            setFlag(Flag.ANSWERED, true)
            internalDate = Date(1_762_700_000_000)
        }

        val item = message.toMailItem(account = "alice@gmail.com", folder = SyncedFolder("INBOX", FolderRole.INBOX), uid = 42)

        assertEquals("alice@gmail.com", item.account)
        assertEquals("INBOX", item.folder)
        assertEquals(FolderRole.INBOX, item.role)
        assertEquals(42L, item.uid)
        assertEquals(MailAddress("Renée", "renee@example.org"), item.from)
        assertEquals(listOf(MailAddress("Alice", "alice@gmail.com"), MailAddress(null, "bob@example.org")), item.to)
        assertEquals("Réunion de lundi", item.subject)
        assertEquals(1_762_700_000_000, item.receivedAt)
        assertEquals(MailFlags(read = true, flagged = false, answered = true), item.flags)
        assertEquals("<c@example.org>", item.messageId)
        assertFalse(item.hasAttachment)
    }

    @Test
    fun `a conversation is keyed by the first message it refers to`() {
        assertEquals("<a@example.org>", parse(plain).threadKey())
        assertEquals("<b@example.org>", parse("Message-ID: <c@x>\nIn-Reply-To: <b@example.org>\n\nx").threadKey())
        assertEquals("<c@x>", parse("Message-ID: <c@x>\n\nx").threadKey())
        assertNull(parse("Subject: x\n\nx").threadKey())
    }

    @Test
    fun `without an arrival date, the Date header is used`() {
        val item = parse(plain).toMailItem("a", SyncedFolder("INBOX", FolderRole.INBOX), 1)
        assertEquals(1_794_214_800_000, item.receivedAt)
    }

    private val withAttachment = """
        From: a@example.org
        Content-Type: multipart/mixed; boundary=XX

        --XX
        Content-Type: text/plain; charset=utf-8

        voir pièce jointe
        --XX
        Content-Type: application/pdf; name=facture.pdf
        Content-Disposition: attachment; filename=facture.pdf
        Content-Transfer-Encoding: base64

        JVBERi0=
        --XX--
    """

    private val inlineImage = """
        From: a@example.org
        Content-Type: multipart/related; boundary=YY

        --YY
        Content-Type: multipart/alternative; boundary=ZZ

        --ZZ
        Content-Type: text/plain

        texte
        --ZZ
        Content-Type: text/html

        <p>html <img src="cid:logo"></p>
        --ZZ--
        --YY
        Content-Type: image/png; name=logo.png
        Content-Disposition: inline; filename=logo.png
        Content-ID: <logo>
        Content-Transfer-Encoding: base64

        iVBORw0=
        --YY--
    """

    @Test
    fun `an attachment is a part sent as one, not an image shown in the text`() {
        assertTrue(parse(withAttachment).hasAttachment())
        assertFalse(parse(inlineImage).hasAttachment())
        assertFalse(parse(plain).hasAttachment())
        assertTrue(parse("Content-Type: application/zip; name=a.zip\n\nUEs=").hasAttachment())
    }

    @Test
    fun `the readable body keeps both the plain and the HTML alternative`() {
        assertEquals(MailBody(text = "texte", html = "<p>html <img src=\"cid:logo\"></p>"), parse(inlineImage).readableBody())
        assertEquals(MailBody(text = "voir pièce jointe", html = null), parse(withAttachment).readableBody())
        assertEquals(MailBody(text = "Bonjour", html = null), parse(plain).readableBody())
    }
}
