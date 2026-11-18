package dev.bold7.messages

import dev.bold7.hub.data.Box
import dev.bold7.hub.data.ItemKind
import dev.bold7.hub.data.MailResult
import dev.bold7.ui.textActs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailTest {
    private val renee = Addr("renee@example.org", "Renée")
    private val bob = Addr("bob@example.org")
    private val me = "me@example.org"
    private val opened = Opened(
        from = renee, to = listOf(Addr(me, "Me"), bob), cc = listOf(Addr("carol@example.org", "Carol")), replyTo = emptyList(),
        subject = "Réunion", date = 0, messageId = "<b@x>", references = listOf("<a@x>"), text = "On se voit lundi ?\nRenée", own = me,
    )
    private val date = { _: Long -> "Mon 16 Nov 2026 10:00" }

    @Test
    fun `addresses are read from what is typed, names and quotes included`() {
        assertEquals(listOf(renee, bob), parseAddresses("Renée <renee@example.org>, bob@example.org ;"))
        assertEquals(listOf(Addr("j@x.org", "Doe, John")), parseAddresses("\"Doe, John\" <j@x.org>"))
        assertEquals(emptyList(), parseAddresses("  "))
        assertNull(parseAddresses("Renée"), "a name without its address is not sent")
        assertNull(parseAddresses("bob@example"))
        assertEquals("\"Doe, John\" <j@x.org>, bob@example.org", formatAddresses(listOf(Addr("j@x.org", "Doe, John"), bob)))
    }

    @Test
    fun `reply goes to the sender, reply all to everyone else but me, threaded`() {
        val r = reply(opened, "mail-1", all = false, date)
        assertEquals("Renée <renee@example.org>", r.to)
        assertEquals("", r.cc)
        assertEquals("Re: Réunion", r.subject)
        assertEquals("<b@x>", r.inReplyTo)
        assertEquals(listOf("<a@x>", "<b@x>"), r.references)
        assertTrue(r.text.startsWith("\n\n-----Original Message-----\nFrom: Renée <renee@example.org>\nSent: Mon 16 Nov 2026 10:00\n"))
        assertTrue(r.text.endsWith("Subject: Réunion\n\nOn se voit lundi ?\nRenée"))

        val all = reply(opened, "mail-1", all = true, date)
        assertEquals("bob@example.org, Carol <carol@example.org>", all.cc)
        assertEquals("Re: Réunion", reply(opened.copy(subject = "RE: Réunion"), "mail-1", false, date).subject)
    }

    @Test
    fun `a reply to my own sent mail goes to its recipients, reply-to wins over from`() {
        assertEquals("bob@example.org", reply(opened.copy(from = Addr(me)), "mail-1", false, date).to)
        assertEquals("list@example.org", reply(opened.copy(replyTo = listOf(Addr("list@example.org"))), "mail-1", false, date).to)
    }

    @Test
    fun `forward starts empty, the original below, Fw once`() {
        val f = forward(opened, "mail-1", date)
        assertEquals("", f.to)
        assertEquals("Fw: Réunion", f.subject)
        assertNull(f.inReplyTo)
        assertEquals("Fw: Réunion", forward(opened.copy(subject = "Fw: Réunion"), "mail-1", date).subject)
        assertEquals("Re: x", subjectWith("Re: ", "re: x"))
    }

    @Test
    fun `a draft is sendable once its recipients are addresses`() {
        val d = MailDraft(account = "mail-1", to = "Renée", subject = "s", text = "t")
        assertFalse(d.sendable)
        assertTrue(d.copy(to = "renee@example.org").sendable)
        assertFalse(d.copy(to = "", cc = "").sendable)
        assertTrue(d.copy(to = "", cc = "bob@example.org").sendable)
        assertFalse(d.copy(to = "bob@example.org", cc = "nope").sendable)
        assertFalse(MailDraft(account = null, to = "bob@example.org").sendable, "no account, nothing leaves")
    }

    @Test
    fun `a draft survives being kept, and shows in the inbox as a draft`() {
        val d = MailDraft(id = "d1", account = "mail-1", to = "Renée <renee@example.org>", cc = "", subject = "Objet\nlong", text = "Ligne 1\nLigne 2", inReplyTo = "<b@x>", references = listOf("<a@x>", "<b@x>"), savedAt = 42)
        assertEquals(d, draftOf(d.encoded()))
        val item = d.asItem()
        assertEquals(DRAFTS_ACCOUNT, item.accountId)
        assertEquals("d1", item.sourceKey)
        assertEquals(ItemKind.EMAIL, item.kind)
        assertEquals(Box.DRAFT, item.box)
        assertEquals("Renée", item.sender)
        assertTrue(item.read)
        assertEquals(42, item.timestamp)
        assertNull(draftOf("garbage"))
    }

    @Test
    fun `typing a name in To proposes the contacts' addresses, choosing one replaces what was typed`() {
        val contacts = listOf(EmailContact("Renée Martin", listOf("renee@example.org", "rm@work.org")), EmailContact("Bob", listOf("bob@example.org")))
        assertEquals(listOf(Addr("renee@example.org", "Renée Martin"), Addr("rm@work.org", "Renée Martin")), emailSuggestions(contacts, "bob@example.org, ren"))
        assertEquals(emptyList(), emailSuggestions(contacts, "bob@exa"))
        assertEquals(emptyList(), emailSuggestions(contacts, ""))
        assertEquals("bob@example.org, Renée Martin <renee@example.org>, ", withChosen("bob@example.org, ren", Addr("renee@example.org", "Renée Martin")))
    }

    @Test
    fun `setup asks for the servers only when the domain is unknown, and says why it failed`() {
        var s = SetupState(address = "alice@example.org", password = "pw")
        assertTrue(s.ready)
        assertTrue(SetupState(address = "alice@example.org").ready, "Next without a password asks what the address needs")
        assertFalse(SetupState(address = "alice", password = "pw").ready)
        s = s.answered(MailResult.SERVERS)
        assertTrue(s.servers)
        assertFalse(s.ready, "the servers are to be typed")
        s = s.copy(imapHost = "imap.example.org", smtpHost = "smtp.example.org")
        assertTrue(s.ready)
        assertFalse(s.copy(password = "").ready, "typed servers need the password")
        assertFalse(s.copy(imapPort = "x").ready)
        assertEquals(SetupNotice.AUTH, s.answered(MailResult.AUTH).notice)
        assertEquals(SetupNotice.NETWORK, s.answered(MailResult.NETWORK).notice)
        assertFalse(s.copy(busy = true).answered(MailResult.AUTH).busy)
    }

    @Test
    fun `each provider says what it needs, for a refused password as for none typed`() {
        fun notice(result: MailResult, provider: String?) = SetupState(address = "a@b.c").answered(SetupAnswer(result, provider)).notice
        val needs = mapOf(
            "gmail" to SetupNotice.AUTH_GMAIL, "icloud" to SetupNotice.AUTH_ICLOUD, "yahoo" to SetupNotice.AUTH_YAHOO,
            "aol" to SetupNotice.AUTH_AOL, "gmx" to SetupNotice.AUTH_GMX, "webde" to SetupNotice.AUTH_WEBDE,
            "mailcom" to SetupNotice.AUTH_MAILCOM, "zoho" to SetupNotice.AUTH_ZOHO, "yandex" to SetupNotice.AUTH_YANDEX,
            "orange" to SetupNotice.AUTH_ORANGE,
        )
        for ((provider, n) in needs) {
            assertEquals(n, notice(MailResult.AUTH, provider), provider)
            assertEquals(n, notice(MailResult.PASSWORD, provider), provider)
        }
        // A normal password (the French ISPs, typed servers): the plain words.
        for (provider in listOf("free", "sfr", "laposte", "bbox", "custom", null)) {
            assertEquals(SetupNotice.AUTH, notice(MailResult.AUTH, provider))
            assertEquals(SetupNotice.PASSWORD, notice(MailResult.PASSWORD, provider))
        }
        assertEquals(SetupNotice.BROWSER, notice(MailResult.BROWSER, "outlook"))
        assertEquals(SetupNotice.BROWSER_OFF, notice(MailResult.UNAVAILABLE, "outlook"), "Outlook without a client in this build: said, never tried")
        assertEquals(SetupNotice.WORK_ACCOUNT, notice(MailResult.UNAVAILABLE, "microsoft365"))
        assertEquals(SetupNotice.UNAVAILABLE, notice(MailResult.UNAVAILABLE, "yahoo"))
        assertEquals(SetupNotice.BRIDGE, notice(MailResult.BRIDGE, "proton"))
        for (r in listOf(MailResult.HOST_UNKNOWN, MailResult.PORT_CLOSED, MailResult.TIMEOUT, MailResult.NO_STARTTLS, MailResult.TLS, MailResult.NO_PASSWORD_LOGIN)) {
            assertEquals(r.name, notice(r, "custom")!!.name)
        }
    }

    @Test
    fun `the security of a typed server follows its port until chosen`() {
        val s = SetupState(servers = true, imapPort = "143", smtpPort = "465")
        assertTrue(s.imapStartTls)
        assertFalse(s.smtpStartTls)
        assertFalse(s.copy(imapPort = "993").imapStartTls)
        assertFalse(s.copy(imapSecurity = false).imapStartTls, "chosen: kept whatever the port")
        assertTrue(s.copy(smtpSecurity = true).smtpStartTls)
    }

    @Test
    fun `servers found by apps-hub come back to be corrected, with their login name`() {
        val found = FoundServers("imap.example.org", 993, false, "smtp.example.org", 587, true, "alice")
        val s = SetupState(address = "alice@example.org", password = "pw").answered(SetupAnswer(MailResult.AUTH, "custom", "imap.example.org:993", "Invalid credentials", found = found))
        assertTrue(s.servers)
        assertEquals("imap.example.org", s.imapHost)
        assertEquals("587", s.smtpPort)
        assertEquals(true, s.smtpSecurity)
        assertEquals("alice", s.user)
        assertEquals("imap.example.org:993", s.server)
        assertEquals("Invalid credentials", s.detail)
    }

    @Test
    fun `a certificate is accepted for its server only, when apps-hub allows it`() {
        val sha = (1..32).joinToString(":") { "0A" }
        val cert = CertificatePrompt("mail.example.org:993", sha, listOf("other.example.net"), "other.example.net", selfSigned = true, expired = false, acceptable = true)
        var s = SetupState(address = "alice@example.org", password = "pw", servers = true).answered(SetupAnswer(MailResult.CERTIFICATE, "custom", "mail.example.org:993", certificate = cert))
        assertEquals(SetupNotice.CERTIFICATE, s.notice)
        assertEquals(cert, s.certificate)
        s = s.accepted()
        assertEquals(listOf("mail.example.org 993 $sha"), s.accepted)
        assertNull(s.certificate)
        // Not acceptable (a known provider's): accepting does nothing.
        val known = SetupState().answered(SetupAnswer(MailResult.CERTIFICATE, "gmail", certificate = cert.copy(acceptable = false)))
        assertEquals(emptyList(), known.accepted().accepted)
    }

    // OS 7's signature is "added to your email after you send it" (guide 7.1 p. 90, B),
    // under what I wrote and above the original (S).
    @Test fun theSignatureGoesUnderTheTextAboveTheOriginal() {
        val sig = "Sent from my BlackBerry\u00AE smartphone"
        assertEquals("Merci\n$sig", signed("Merci", sig))
        assertEquals("Merci\n$sig", signed("Merci\n\n", sig))
        assertEquals(sig, signed("", sig))
        val replied = "D'accord" + quoted(opened, date)
        val s = signed(replied, sig)
        assertTrue(s.startsWith("D'accord\n$sig\n\n-----Original Message-----\n"), s)
        assertTrue(s.endsWith(opened.text))
        assertEquals("$sig\n\n-----Original Message-----", signed("\n\n-----Original Message-----", sig))
        assertEquals("Merci", signed("Merci", null))
    }

    @Test fun theBrowsersAnswersAreSaid() {
        assertEquals(SetupNotice.UNAVAILABLE, SetupState().answered(MailResult.UNAVAILABLE).notice)
        assertEquals(SetupNotice.CANCELLED, SetupState().answered(MailResult.CANCELLED).notice)
    }

    @Test fun composeMenuHasTheSelectionEntriesBeforeClose() {
        val acts = { sel: Boolean, paste: Boolean -> textActs(sel, paste) }
        assertEquals(
            listOf(ComposeAct.SEND, ComposeAct.SWITCH, ComposeAct.SAVE_DRAFT, ComposeAct.START_SELECTION, ComposeAct.CLOSE),
            composeMenu(sendable = true, text = acts(false, false)).entries.map { it.action },
        )
        // Switch Application first when nothing can be sent; Cut, Copy and Paste on a selection with a clipboard.
        val menu = composeMenu(sendable = false, text = acts(true, true))
        assertEquals(
            listOf(ComposeAct.SWITCH, ComposeAct.SAVE_DRAFT, ComposeAct.CUT, ComposeAct.COPY, ComposeAct.PASTE, ComposeAct.CLOSE),
            menu.entries.map { it.action },
        )
        // No field in focus (the account line): no selection entries.
        assertEquals(listOf(ComposeAct.SWITCH, ComposeAct.SAVE_DRAFT, ComposeAct.CLOSE), composeMenu(false, emptyList()).entries.map { it.action })
    }
}
