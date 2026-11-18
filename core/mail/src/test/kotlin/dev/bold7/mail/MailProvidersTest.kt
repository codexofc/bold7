package dev.bold7.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MailProvidersTest {
    @Test
    fun `the big providers are known by the domains they publish`() {
        val cases = mapOf(
            "outlook" to listOf("hotmail.com", "hotmail.co.uk", "hotmail.fr", "live.fr", "live.com.mx", "outlook.de", "outlook.com.br", "msn.com", "windowslive.com", "livemail.tw"),
            "yahoo" to listOf("yahoo.com", "yahoo.fr", "yahoo.co.uk", "ymail.com", "rocketmail.com", "myyahoo.com"),
            "aol" to listOf("aol.com", "aim.com", "aol.fr", "netscape.net"),
            "gmx" to listOf("gmx.de", "gmx.net", "gmx.at", "gmx.ch", "gmx.fr", "gmx.com", "gmx.co.uk"),
            "webde" to listOf("web.de"),
            "mailcom" to listOf("mail.com", "email.com", "usa.com", "myself.com"),
            "zoho" to listOf("zoho.com", "zohomail.com"),
            "yandex" to listOf("yandex.ru", "yandex.com", "ya.ru", "narod.ru"),
            "proton" to listOf("proton.me", "protonmail.com", "protonmail.ch", "pm.me"),
            "orange" to listOf("orange.fr", "wanadoo.fr"),
            "free" to listOf("free.fr"),
            "sfr" to listOf("sfr.fr", "neuf.fr", "club-internet.fr"),
            "laposte" to listOf("laposte.net"),
            "bbox" to listOf("bbox.fr"),
            "icloud" to listOf("icloud.com", "me.com", "mac.com"),
            "gmail" to listOf("gmail.com", "googlemail.com"),
        )
        for ((id, domains) in cases) for (d in domains) assertEquals(id, MailProviders.forAddress("alice@$d")?.id, d)
    }

    @Test
    fun `each provider has its published servers, over TLS`() {
        fun servers(address: String) = MailProviders.forAddress(address)!!.let { listOf(it.imap, it.smtp) }
        assertEquals(listOf(Endpoint("imap.mail.yahoo.com", 993), Endpoint("smtp.mail.yahoo.com", 465)), servers("a@yahoo.fr"))
        assertEquals(listOf(Endpoint("imap.gmx.net", 993), Endpoint("mail.gmx.net", 465)), servers("a@gmx.de"))
        assertEquals(listOf(Endpoint("imap.gmx.com", 993), Endpoint("mail.gmx.com", 465)), servers("a@gmx.fr"))
        assertEquals(listOf(Endpoint("imap.orange.fr", 993), Endpoint("smtp.orange.fr", 465)), servers("a@wanadoo.fr"))
        assertEquals(listOf(Endpoint("imap.free.fr", 993), Endpoint("smtp.free.fr", 465)), servers("a@free.fr"))
        assertEquals(listOf(Endpoint("outlook.office365.com", 993), Endpoint.startTls("smtp-mail.outlook.com", 587)), servers("a@hotmail.co.uk"))
        for (p in MailProviders.all) for (e in listOf(p.imap, p.smtp)) assertEquals(true, e.tls, p.id)
    }

    @Test
    fun `outlook signs in through the browser only, proton only through its bridge, the others by password`() {
        assertEquals(Login.BROWSER, MailProviders.forAddress("a@hotmail.com")!!.login)
        assertEquals(Login.BRIDGE, MailProviders.forAddress("a@proton.me")!!.login)
        assertEquals(Login.PASSWORD, MailProviders.forAddress("a@yahoo.com")!!.login)
        assertEquals(Login.PASSWORD, MailProviders.forAddress("a@gmail.com")!!.login)
        assertEquals(Login.BROWSER, MailProviders.MICROSOFT_365.login)
        assertEquals(Login.BROWSER, MailProviders.byId("outlook")!!.login)
    }

    @Test
    fun `an address is read whatever its case, its spaces, its trailing dot or its script`() {
        assertEquals("gmx", MailProviders.forAddress("  Alice.Martin@GMX.De. ")?.id)
        assertEquals("alice@xn--bcher-kva.example", normalizedAddress(" alice@Bücher.example "))
        assertEquals("xn--bcher-kva.example", domainOf("alice@bücher.example"))
        assertEquals("Alice.Martin@gmx.de", normalizedAddress("Alice.Martin@GMX.DE"), "the local part keeps its case")
        assertNull(normalizedAddress("alice@"))
        assertNull(normalizedAddress("@example.org"))
        assertNull(normalizedAddress("alice@exa mple.org"))
        assertNull(normalizedAddress("alice@example"))
        assertNull(normalizedAddress("alice@ex/ample.org"), "never a domain that would bend a URL")
        assertNull(normalizedAddress("alice@ex?ample.org"))
        assertNull(MailProviders.forAddress("alice@notgmail.com"))
    }

    @Test
    fun `typed servers take the security chosen, or the port's usual one`() {
        val p = MailProviders.custom("mail.example.org", 993, null, "smtp.example.org", 2525, startTlsSmtp = true)
        assertEquals(MailProviders.CUSTOM, p.id)
        assertEquals(Endpoint("mail.example.org", 993), p.imap)
        assertEquals(Endpoint.startTls("smtp.example.org", 2525), p.smtp)
        assertEquals(Endpoint("mail.example.org", 143), MailProviders.custom("mail.example.org", 143, false, "s", 465, null).imap)
        assertEquals(Endpoint.startTls("s", 587), MailProviders.custom("m", 993, null, "s", 587, null).smtp)
        assertEquals(true, MailProviders.usualStartTls(143))
        assertEquals(false, MailProviders.usualStartTls(993))
    }

    @Test
    fun `the login name can differ from the address, for both servers`() {
        val account = MailAccount("alice@example.org", "pw", MailProviders.custom("m", 993, null, "s", 465, null), user = "alice")
        assertEquals("alice", account.imapSettings().username)
        assertEquals("alice", account.smtpSettings().username)
        assertEquals("alice@example.org", MailAccount("alice@example.org", "pw", MailProviders.GMAIL).imapSettings().username)
    }
}
