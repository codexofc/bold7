package dev.bold7.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DiscoveryTest {
    private fun resource(path: String) = javaClass.getResourceAsStream("/$path")!!.use { it.readBytes() }
    private fun xml(name: String) = resource("autoconfig/$name").decodeToString()

    @Test
    fun `an ISPDB answer with several servers gives the implicit TLS ones and the login it names`() {
        // Thunderbird's ISPDB, as served in November 2026: IMAP and SMTP each over SSL and STARTTLS.
        val found = parseAutoconfig(xml("gmx.net.xml"), "alice@gmx.net")!!
        assertEquals(Endpoint("imap.gmx.net", 993), found.provider.imap)
        assertEquals(Endpoint("mail.gmx.net", 465), found.provider.smtp)
        assertEquals(MailProviders.CUSTOM, found.provider.id)
        assertEquals("alice@gmx.net", found.user)
    }

    @Test
    fun `a login name of the local part only is the local part`() {
        val found = parseAutoconfig(xml("free.fr.xml"), "Alice.Martin@free.fr")!!
        assertEquals("Alice.Martin", found.user)
        assertEquals(Endpoint("imap.free.fr", 993), found.provider.imap)
    }

    @Test
    fun `servers that take OAuth only are no help for a password`() {
        assertNull(parseAutoconfig(xml("outlook.com.xml"), "alice@outlook.com"))
    }

    @Test
    fun `STARTTLS alone, an unusual port, a domain in the host name`() {
        val config = """
            <?xml version="1.0"?>
            <clientConfig version="1.1"><emailProvider id="example.org">
              <incomingServer type="pop3"><hostname>pop.example.org</hostname><port>995</port><socketType>SSL</socketType><username>%EMAILADDRESS%</username><authentication>password-cleartext</authentication></incomingServer>
              <incomingServer type="imap"><hostname>imap.%EMAILDOMAIN%</hostname><port>1143</port><socketType>STARTTLS</socketType><username>%EMAILADDRESS%</username><authentication>password-encrypted</authentication></incomingServer>
              <incomingServer type="imap"><hostname>plain.example.org</hostname><port>143</port><socketType>plain</socketType><username>%EMAILADDRESS%</username><authentication>password-cleartext</authentication></incomingServer>
              <outgoingServer type="smtp"><hostname>smtp.example.org</hostname><port>2525</port><socketType>STARTTLS</socketType><username>%EMAILADDRESS%</username><authentication>password-cleartext</authentication></outgoingServer>
            </emailProvider></clientConfig>
        """.trimIndent()
        val found = parseAutoconfig(config, "bob@example.org")!!
        assertEquals(Endpoint.startTls("imap.example.org", 1143), found.provider.imap)
        assertEquals(Endpoint.startTls("smtp.example.org", 2525), found.provider.smtp)
    }

    @Test
    fun `a server in clear only, a DOCTYPE, or something else than a configuration is nothing`() {
        val clear = xml("gmx.net.xml").replace("<socketType>SSL</socketType>", "<socketType>plain</socketType>").replace("<socketType>STARTTLS</socketType>", "<socketType>plain</socketType>")
        assertNull(parseAutoconfig(clear, "alice@gmx.net"))
        assertNull(parseAutoconfig("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + xml("gmx.net.xml"), "alice@gmx.net"))
        assertNull(parseAutoconfig("<html><body>Not found</body></html>", "alice@gmx.net"))
        assertNull(parseAutoconfig("not xml at all <", "alice@gmx.net"))
    }

    @Test
    fun `a DNS answer gives its MX hosts, the most preferred first`() {
        // gmail.com's MX, as 1.1.1.1 answered in November 2026 (names compressed).
        assertEquals(
            listOf("gmail-smtp-in.l.google.com", "alt1.gmail-smtp-in.l.google.com", "alt2.gmail-smtp-in.l.google.com", "alt3.gmail-smtp-in.l.google.com", "alt4.gmail-smtp-in.l.google.com"),
            parseMx(resource("dns/mx-gmail.com.bin")),
        )
        assertEquals(listOf("mail.protonmail.ch", "mailsec.protonmail.ch"), parseMx(resource("dns/mx-protonmail.com.bin")))
        assertEquals(emptyList(), parseMx(resource("dns/mx-gmail.com.bin").copyOf(40)), "cut short")
        assertEquals(emptyList(), parseMx(ByteArray(3)))
    }

    @Test
    fun `a domain received by Google, Microsoft or Proton is theirs`() {
        assertSame(MailProviders.GMAIL, mxProvider(listOf("aspmx.l.google.com", "alt1.aspmx.l.google.com")))
        assertSame(MailProviders.GMAIL, mxProvider(listOf("smtp.google.com")))
        assertSame(MailProviders.MICROSOFT_365, mxProvider(listOf("example-org.mail.protection.outlook.com")))
        assertSame(MailProviders.PROTON, mxProvider(listOf("mail.protonmail.ch")))
        assertNull(mxProvider(listOf("mx.example.org")))
        assertNull(mxProvider(listOf("google.com.evil.example")))
    }

    private class Fetch(private val answers: Map<String, String>) : ConfigFetch {
        val asked = mutableListOf<String>()
        override fun get(url: String): String? = answers[url].also { asked += url }
    }

    @Test
    fun `discovery asks the ISPDB, then the domain, then its MX, and stops at the first answer`() {
        val gmx = xml("gmx.net.xml")
        val ispdb = Fetch(mapOf("https://autoconfig.thunderbird.net/v1.1/example.org" to gmx))
        assertEquals("imap.gmx.net", discover("bob@Example.org", ispdb) { error("no MX needed") }!!.provider.imap.host)
        assertEquals(listOf("https://autoconfig.thunderbird.net/v1.1/example.org"), ispdb.asked)

        val own = Fetch(mapOf("https://autoconfig.example.org/mail/config-v1.1.xml" to gmx))
        assertEquals("imap.gmx.net", discover("bob@example.org", own) { emptyList() }!!.provider.imap.host)

        val none = Fetch(emptyMap())
        val workspace = discover("bob@example.org", none) { listOf("aspmx.l.google.com") }!!
        assertSame(MailProviders.GMAIL, workspace.provider)
        assertEquals("bob@example.org", workspace.user)

        // A host whose ISPDB entry is known (Thunderbird's way for hosted domains): its base domain asked.
        val hosted = Fetch(mapOf("https://autoconfig.thunderbird.net/v1.1/gmx.net" to gmx))
        assertEquals("imap.gmx.net", discover("bob@example.org", hosted) { listOf("mx00.gmx.net") }!!.provider.imap.host)

        assertNull(discover("bob@example.org", Fetch(emptyMap())) { emptyList() }, "nothing found, no network: the servers are typed")
        assertNull(discover("bob@", Fetch(emptyMap())) { error("never") })
    }

    @Test
    fun `an IDN domain is asked in ASCII`() {
        val fetch = Fetch(emptyMap())
        discover("bob@bücher.example", fetch) { emptyList() }
        assertEquals("https://autoconfig.thunderbird.net/v1.1/xn--bcher-kva.example", fetch.asked.first())
    }
}
