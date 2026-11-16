package dev.bold7.mail

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import java.io.File
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The key that seals [AccountVault]: AES-256-GCM, created on first use inside the Android
 * Keystore (TEE-backed on the phone), never exportable, usable only by this app's uid. Removing the app
 * destroys it, and with it any way to read the stored password.
 */
object MailKeystore {
    private const val ALIAS = "bold7-mail-account"

    /**
     * Account [id]'s vault, one file per account, in the no-backup directory: a
     * sealed account never goes to a backup. [id] is the Hub's opaque one, no address in a name.
     */
    fun vault(context: Context, id: String): AccountVault {
        require(id.matches(ID)) { "Bad account id" }
        return AccountVault(File(dir(context), "$id.bin"), ::key)
    }

    /** The ids of the accounts sealed so far. */
    fun ids(context: Context): List<String> =
        dir(context).listFiles().orEmpty().map { it.name }.filter { it.endsWith(".bin") }.map { it.removeSuffix(".bin") }.filter { it.matches(ID) }.sorted()

    private val ID = Regex("[a-z0-9-]{1,40}")

    private fun dir(context: Context) = File(context.noBackupFilesDir, "mail").apply { mkdirs() }

    @Synchronized
    fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
}
