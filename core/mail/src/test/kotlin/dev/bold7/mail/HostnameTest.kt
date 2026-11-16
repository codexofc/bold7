package dev.bold7.mail

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostnameTest {
    @Test
    fun `an exact name matches, whatever the case`() {
        assertTrue(hostnameMatches("imap.gmail.com", "imap.gmail.com"))
        assertTrue(hostnameMatches("IMAP.Gmail.com", "imap.gmail.COM."))
        assertFalse(hostnameMatches("imap.gmail.com", "smtp.gmail.com"))
        assertFalse(hostnameMatches("imap.gmail.com.evil.org", "imap.gmail.com"))
    }

    @Test
    fun `a wildcard stands for exactly one leftmost label`() {
        assertTrue(hostnameMatches("imap.gmail.com", "*.gmail.com"))
        assertFalse(hostnameMatches("gmail.com", "*.gmail.com"))
        assertFalse(hostnameMatches("a.imap.gmail.com", "*.gmail.com"))
        assertFalse(hostnameMatches("imapgmail.com", "*.gmail.com"))
    }

    @Test
    fun `no wildcard under a top-level domain, nor elsewhere than leftmost`() {
        assertFalse(hostnameMatches("gmail.com", "*.com"))
        assertFalse(hostnameMatches("imap.gmail.com", "imap.*.com"))
        assertFalse(hostnameMatches("imap.gmail.com", "im*.gmail.com"))
        assertFalse(hostnameMatches("imap.gmail.com", "*.*.com"))
    }
}
