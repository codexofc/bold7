package dev.bold7.mail

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AccountVaultTest {
    // On the phone the key lives in the Android Keystore; here a plain AES key stands in for it.
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val file = File(Files.createTempDirectory("vault").toFile(), "account.bin")
    private val vault = AccountVault(file) { key }
    private val account = MailAccount("alice@gmail.com", "abcd efgh ijkl mnop", MailProviders.GMAIL)

    @Test
    fun `no account until one is saved`() {
        assertNull(vault.load())
    }

    @Test
    fun `an account survives a round trip`() {
        vault.save(account)
        assertEquals(account, AccountVault(file) { key }.load())
    }

    @Test
    fun `typed servers survive a round trip, still over TLS`() {
        val typed = MailAccount("alice@example.org", "pw", MailProviders.custom("imap.example.org", 993, "smtp.example.org", 587))
        vault.save(typed)
        assertEquals(typed, AccountVault(file) { key }.load())
    }

    @Test
    fun `a login name, accepted certificates and the provider's way of logging in survive a round trip`() {
        val pin = Pin("mail.example.org", 993, (1..32).joinToString(":") { "AB" })
        val typed = MailAccount("alice@example.org", "pw", MailProviders.custom("mail.example.org", 993, "smtp.example.org", 587), user = "alice", pins = listOf(pin))
        vault.save(typed)
        assertEquals(typed, AccountVault(file) { key }.load())
        val outlook = MailAccount("alice@hotmail.fr", "1//refresh", MailProviders.OUTLOOK, oauth = "microsoft")
        vault.save(outlook)
        assertEquals(outlook, AccountVault(file) { key }.load())
        assertEquals(pin, Pin.parse(pin.toString()))
        assertNull(Pin.parse("mail.example.org 993 not-a-fingerprint"))
    }

    @Test
    fun `nothing of the account is stored in clear`() {
        vault.save(account)
        val stored = String(file.readBytes(), Charsets.ISO_8859_1)
        for (secret in listOf("abcd efgh", "alice@gmail.com", "gmail")) assertFalse(secret in stored, secret)
    }

    @Test
    fun `two saves of the same account do not produce the same bytes`() {
        vault.save(account)
        val first = file.readBytes()
        vault.save(account)
        assertFalse(first.contentEquals(file.readBytes()))
    }

    @Test
    fun `a modified file is erased, not half read`() {
        vault.save(account)
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(vault.load())
        assertFalse(file.exists())
    }

    @Test
    fun `another key cannot read the account`() {
        vault.save(account)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(AccountVault(file) { other }.load())
        assertFalse(file.exists(), "a key lost with a reinstall: Alice enters the account again")
    }

    @Test
    fun `an account signed in through the browser keeps its refresh token and its signature`() {
        val oauth = MailAccount("alice@gmail.com", "1//refresh-token", MailProviders.GMAIL, oauth = "google", signature = "Envoyé de mon 9900")
        vault.save(oauth)
        assertEquals(oauth, AccountVault(file) { key }.load())
        assertFalse("1//refresh" in String(file.readBytes(), Charsets.ISO_8859_1))
        val unsigned = account.copy(signature = null)
        vault.save(unsigned)
        assertEquals(unsigned, vault.load())
    }

    @Test
    fun `a version 2 file still opens, with an app password and OS 7's signature`() {
        val plain = java.io.ByteArrayOutputStream().also { b ->
            java.io.DataOutputStream(b).use {
                it.writeUTF("alice@gmail.com")
                it.writeUTF("gmail")
                for ((host, port) in listOf("imap.gmail.com" to 993, "smtp.gmail.com" to 465)) {
                    it.writeUTF(host)
                    it.writeInt(port)
                    it.writeBoolean(false)
                }
                it.writeUTF("abcd efgh ijkl mnop")
            }
        }.toByteArray()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            updateAAD("bold7-mail-account".toByteArray())
        }
        val sealed = cipher.doFinal(plain)
        file.writeBytes(byteArrayOf(2, cipher.iv.size.toByte()) + cipher.iv + sealed)
        assertEquals(account, vault.load())
        assertEquals(SIGNATURE, vault.load()?.signature)
        assertNull(vault.load()?.oauth)
    }

    @Test
    fun `clear forgets the account`() {
        vault.save(account)
        vault.clear()
        assertNull(vault.load())
    }
}
