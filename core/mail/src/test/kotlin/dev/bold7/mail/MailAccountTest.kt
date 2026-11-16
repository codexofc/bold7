package dev.bold7.mail

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class MailAccountTest {
    @Test
    fun `gmail addresses resolve to the gmail servers, over implicit TLS`() {
        for (address in listOf("alice@gmail.com", "Alice@GoogleMail.com", " alice@gmail.com ")) {
            assertSame(MailProviders.GMAIL, MailProviders.forAddress(address), address)
        }
        assertEquals(Endpoint("imap.gmail.com", 993), MailProviders.GMAIL.imap)
        assertEquals(Endpoint("smtp.gmail.com", 465), MailProviders.GMAIL.smtp)
        assertSame(MailProviders.GMAIL, MailProviders.byId("gmail"))
    }

    @Test
    fun `an unknown domain or a malformed address has no provider`() {
        assertNull(MailProviders.forAddress("alice@example.org"))
        assertNull(MailProviders.forAddress("gmail.com"))
        assertNull(MailProviders.forAddress("alice@"))
        assertNull(MailProviders.byId("yahoo"))
    }

    @Test
    fun `server settings log in with the address and the app password, TLS required`() {
        val account = MailAccount("alice@gmail.com", "abcd efgh ijkl mnop", MailProviders.GMAIL)

        val imap = account.imapSettings()
        assertEquals("imap", imap.type)
        assertEquals("imap.gmail.com", imap.host)
        assertEquals(993, imap.port)
        assertEquals(ConnectionSecurity.SSL_TLS_REQUIRED, imap.connectionSecurity)
        assertEquals(AuthType.PLAIN, imap.authenticationType)
        assertEquals("alice@gmail.com", imap.username)
        assertEquals("abcd efgh ijkl mnop", imap.password)

        val smtp = account.smtpSettings()
        assertEquals("smtp", smtp.type)
        assertEquals("smtp.gmail.com", smtp.host)
        assertEquals(465, smtp.port)
        assertEquals(ConnectionSecurity.SSL_TLS_REQUIRED, smtp.connectionSecurity)
    }

    @Test
    fun `outlook and icloud addresses resolve to their servers, SMTP over STARTTLS required`() {
        for (address in listOf("alice@outlook.com", "alice@hotmail.fr", "alice@live.com")) assertSame(MailProviders.OUTLOOK, MailProviders.forAddress(address), address)
        for (address in listOf("alice@icloud.com", "alice@me.com", "alice@mac.com")) assertSame(MailProviders.ICLOUD, MailProviders.forAddress(address), address)

        val account = MailAccount("alice@icloud.com", "pw", MailProviders.ICLOUD)
        assertEquals(ConnectionSecurity.SSL_TLS_REQUIRED, account.imapSettings().connectionSecurity)
        assertEquals("smtp.mail.me.com", account.smtpSettings().host)
        assertEquals(587, account.smtpSettings().port)
        assertEquals(ConnectionSecurity.STARTTLS_REQUIRED, account.smtpSettings().connectionSecurity)
        assertSame(MailProviders.OUTLOOK, MailProviders.byId("outlook"))
    }

    @Test
    fun `typed servers take STARTTLS on their usual ports, implicit TLS on the others, never clear`() {
        val p = MailProviders.custom(" mail.example.org ", 993, "smtp.example.org", 587)
        assertEquals(MailProviders.CUSTOM, p.id)
        assertEquals(Endpoint("mail.example.org", 993), p.imap)
        assertEquals(Endpoint.startTls("smtp.example.org", 587), p.smtp)
        assertEquals(Endpoint.startTls("mail.example.org", 143), MailProviders.custom("mail.example.org", 143, "s", 465).imap)
        assertEquals(Endpoint("s", 465), MailProviders.custom("m", 143, "s", 465).smtp)
    }

    @Test
    fun `an endpoint without TLS is only for the local test server`() {
        val local = MailProvider("test", Endpoint("127.0.0.1", 3143, tls = false), Endpoint("127.0.0.1", 3025, tls = false))
        val account = MailAccount("a@b.c", "pw", local)
        assertEquals(ConnectionSecurity.NONE, account.imapSettings().connectionSecurity)
    }

    @Test
    fun `the password never shows in the account's text`() {
        val account = MailAccount("alice@gmail.com", "s3cret-app-pw", MailProviders.GMAIL)
        assertFalse("s3cret-app-pw" in account.toString())
        assertFalse("s3cret-app-pw" in listOf(account).toString())
    }

    @Test
    fun `an account signed in through the browser logs in by XOAUTH2, no password sent`() {
        val account = MailAccount("alice@gmail.com", "1//refresh-token", MailProviders.GMAIL, oauth = "google")
        for (settings in listOf(account.imapSettings(), account.smtpSettings())) {
            assertEquals(AuthType.XOAUTH2, settings.authenticationType)
            assertEquals("alice@gmail.com", settings.username)
            assertNull(settings.password)
        }
        assertFalse("1//refresh" in account.toString())
    }

    @Test
    fun `a new account signs its mail as OS 7 did`() {
        assertEquals("Sent from my BlackBerry\u00AE smartphone", SIGNATURE)
        assertEquals(SIGNATURE, MailAccount("a@b.c", "pw", MailProviders.GMAIL).signature)
    }
}
