package dev.bold7.mail

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The account, password included, sealed with AES-GCM under [key] in [file]. On the phone the key
 * is non-exportable in the Android Keystore ([MailKeystore]) and the file sits in the app's
 * no-backup directory: neither the password nor the address is ever on disk in clear.
 *
 * File: version byte, IV length, IV, then the GCM ciphertext and tag. Version 2
 * seals the servers with the account: a typed server, Outlook's, iCloud's. Version 3
 * adds how it logs in (app password, or the browser's OAuth server and its refresh token in place of
 * the password) and the signature; version 4 the login name and the accepted certificates (typed
 * servers). A version 2 or 3 file still opens: the address as login name, no certificate, and for
 * version 2 an app password with OS 7's signature. A modified file or another key is erased by
 * [load], never half read.
 */
class AccountVault(private val file: File, private val key: () -> SecretKey) {
    /**
     * The saved account, or null if there is none. A file that does not open (modified, or a key
     * lost with a reinstall) is erased and null comes back: the user enters the account again.
     */
    fun load(): MailAccount? {
        if (!file.exists()) return null
        var version: Byte = VERSION
        val plain = try {
            DataInputStream(file.inputStream().buffered()).use {
                version = it.readByte()
                check(version in V2..VERSION) { "Unknown account file version" }
                val iv = ByteArray(it.readUnsignedByte()).also(it::readFully)
                cipher(Cipher.DECRYPT_MODE, iv).doFinal(it.readBytes())
            }
        } catch (e: GeneralSecurityException) {
            clear()
            return null
        } catch (e: IllegalStateException) {
            // Another version's file (version 1 had no servers): entered again, never half read.
            clear()
            return null
        }
        return DataInputStream(ByteArrayInputStream(plain)).use {
            val address = it.readUTF()
            val providerId = it.readUTF()
            // Rebuilt through the public constructors: a stored endpoint is never a cleartext one.
            fun endpoint(): Endpoint {
                val host = it.readUTF()
                val port = it.readInt()
                return if (it.readBoolean()) Endpoint.startTls(host, port) else Endpoint(host, port)
            }
            val imap = endpoint()
            val smtp = endpoint()
            val password = it.readUTF()
            // How it logs in is the provider's, never stored: rebuilt from its id.
            val provider = MailProvider(providerId, imap, smtp, MailProviders.byId(providerId)?.login ?: Login.PASSWORD)
            if (version == V2) return@use MailAccount(address, password, provider)
            val oauth = it.readUTF().ifEmpty { null }
            val signature = if (it.readBoolean()) it.readUTF() else null
            if (version == V3) return@use MailAccount(address, password, provider, oauth, signature)
            val user = it.readUTF()
            val pins = List(it.readInt()) { _ -> Pin(it.readUTF(), it.readInt(), it.readUTF()) }
            MailAccount(address, password, provider, oauth, signature, user, pins)
        }
    }

    fun save(account: MailAccount) {
        val plain = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeUTF(account.address)
                it.writeUTF(account.provider.id)
                for (e in listOf(account.provider.imap, account.provider.smtp)) {
                    check(e.tls) { "A stored account is never in clear" }
                    it.writeUTF(e.host)
                    it.writeInt(e.port)
                    it.writeBoolean(e.startTls)
                }
                it.writeUTF(account.password)
                it.writeUTF(account.oauth.orEmpty())
                it.writeBoolean(account.signature != null)
                account.signature?.let(it::writeUTF)
                it.writeUTF(account.user)
                it.writeInt(account.pins.size)
                for (p in account.pins) {
                    it.writeUTF(p.host)
                    it.writeInt(p.port)
                    it.writeUTF(p.sha256)
                }
            }
        }.toByteArray()
        // The Android Keystore picks the IV itself (randomized encryption), and so does the JVM.
        val cipher = cipher(Cipher.ENCRYPT_MODE, iv = null)
        val sealed = cipher.doFinal(plain)
        plain.fill(0)
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            DataOutputStream(out).run {
                writeByte(VERSION.toInt())
                writeByte(cipher.iv.size)
                write(cipher.iv)
                write(sealed)
                flush()
            }
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "Cannot replace $file" }
    }

    fun clear() {
        file.delete()
    }

    private fun cipher(mode: Int, iv: ByteArray?) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        if (iv == null) init(mode, key()) else init(mode, key(), GCMParameterSpec(128, iv))
        updateAAD(AAD)
    }

    private companion object {
        const val VERSION: Byte = 4
        const val V3: Byte = 3
        const val V2: Byte = 2
        val AAD = "bold7-mail-account".toByteArray()
    }
}
