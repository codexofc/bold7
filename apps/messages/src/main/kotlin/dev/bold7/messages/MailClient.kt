package dev.bold7.messages

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Email
import android.util.Log
import dev.bold7.hub.data.HubContract
import dev.bold7.hub.data.ItemKey
import dev.bold7.hub.data.MailContract
import dev.bold7.hub.data.MailResult
import java.io.File
import java.util.UUID

/**
 * apps/hub's mail, through its provider's calls (MailContract): Messages holds no
 * account, no password and no network. Blocking binder calls, some waiting on a server: on
 * MessagesGraph's thread only. A refusal or a Hub missing reads as [MailResult.REFUSED].
 */
class MailClient(context: Context) {
    private val resolver = context.contentResolver
    private val hub = Uri.parse("content://${HubContract.AUTHORITY}")

    private fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle? = try {
        resolver.call(hub, method, arg, extras)
    } catch (e: RuntimeException) {
        // Its class only: nothing of a mail nor of an account in the log.
        Log.w(TAG, "mail call refused: ${e.javaClass.name}")
        null
    }

    private fun Bundle?.result() = this?.getString(MailContract.EXTRA_RESULT)?.let { runCatching { MailResult.valueOf(it) }.getOrNull() } ?: MailResult.REFUSED

    /**
     * The accounts in apps/hub's order, and the providers this build signs in through the browser;
     * null when apps/hub did not answer (missing, locked): not the same as no account.
     */
    fun accounts(): MailAccounts? {
        val b = call(MailContract.METHOD_ACCOUNTS)?.takeIf { it.result() == MailResult.OK } ?: return null
        val ids = b.getStringArray(MailContract.EXTRA_IDS).orEmpty()
        val addresses = b.getStringArray(MailContract.EXTRA_ADDRESSES).orEmpty()
        val signatures = b.getStringArray(MailContract.EXTRA_SIGNATURES).orEmpty()
        return MailAccounts(
            ids.indices.map { MailAccountInfo(ids[it], addresses.getOrElse(it) { "" }, signatures.getOrNull(it)?.ifEmpty { null }) },
            b.getStringArray(MailContract.EXTRA_BROWSER).orEmpty().toList(),
        )
    }

    /** Use Auto Signature of account [id]: [text], or off when null (guide 7.1 p. 90, B). */
    fun setSignature(id: String, text: String?): MailResult =
        call(MailContract.METHOD_SIGNATURE, id, Bundle().apply { text?.let { putString(MailContract.EXTRA_SIGNATURE, it) } }).result()

    /** The sign-in page of [address] for the system's browser, apps/hub listening for its redirect. */
    fun browserBegin(address: String): Pair<SetupAnswer, String?> {
        val b = call(MailContract.METHOD_BROWSER_BEGIN, extras = Bundle().apply { putString(MailContract.EXTRA_ADDRESS, address.trim()) })
        return SetupAnswer(b.result(), b?.getString(MailContract.EXTRA_PROVIDER)) to b?.getString(MailContract.EXTRA_URL)
    }

    /** Back from the browser: the sign-in's result, waited for (both servers checked); the new account's id when OK. */
    fun browserFinish(): Pair<MailResult, String?> {
        val b = call(MailContract.METHOD_BROWSER_FINISH)
        return b.result() to b?.getString(MailContract.EXTRA_ID)
    }

    /** The account of [s] logged in to and kept by apps/hub, or why not; its id when [MailResult.OK]. */
    fun add(s: SetupState): Pair<SetupAnswer, String?> {
        val x = Bundle().apply {
            putString(MailContract.EXTRA_ADDRESS, s.address.trim())
            putString(MailContract.EXTRA_PASSWORD, s.password)
            if (s.servers) {
                putString(MailContract.EXTRA_IMAP_HOST, s.imapHost.trim())
                putInt(MailContract.EXTRA_IMAP_PORT, port(s.imapPort) ?: 993)
                putBoolean(MailContract.EXTRA_IMAP_STARTTLS, s.imapStartTls)
                putString(MailContract.EXTRA_SMTP_HOST, s.smtpHost.trim())
                putInt(MailContract.EXTRA_SMTP_PORT, port(s.smtpPort) ?: 465)
                putBoolean(MailContract.EXTRA_SMTP_STARTTLS, s.smtpStartTls)
                putString(MailContract.EXTRA_USER, s.user.trim())
                putStringArray(MailContract.EXTRA_ACCEPT, s.accepted.toTypedArray())
            }
        }
        val b = call(MailContract.METHOD_ADD, extras = x)
        return answer(b) to b?.getString(MailContract.EXTRA_ID)
    }

    private fun answer(b: Bundle?): SetupAnswer {
        val certificate = b?.getString(MailContract.EXTRA_CERT_SHA256)?.let {
            CertificatePrompt(
                server = b.getString(MailContract.EXTRA_SERVER).orEmpty(), sha256 = it,
                names = b.getStringArray(MailContract.EXTRA_CERT_NAMES).orEmpty().toList(),
                issuer = b.getString(MailContract.EXTRA_CERT_ISSUER).orEmpty(),
                selfSigned = b.getBoolean(MailContract.EXTRA_CERT_SELF_SIGNED), expired = b.getBoolean(MailContract.EXTRA_CERT_EXPIRED),
                acceptable = b.getBoolean(MailContract.EXTRA_CERT_ACCEPTABLE),
            )
        }
        val found = b?.getString(MailContract.EXTRA_IMAP_HOST)?.let {
            FoundServers(
                it, b.getInt(MailContract.EXTRA_IMAP_PORT), b.getBoolean(MailContract.EXTRA_IMAP_STARTTLS),
                b.getString(MailContract.EXTRA_SMTP_HOST).orEmpty(), b.getInt(MailContract.EXTRA_SMTP_PORT), b.getBoolean(MailContract.EXTRA_SMTP_STARTTLS),
                b.getString(MailContract.EXTRA_USER).orEmpty(),
            )
        }
        return SetupAnswer(
            b.result(), b?.getString(MailContract.EXTRA_PROVIDER), b?.getString(MailContract.EXTRA_SERVER),
            b?.getString(MailContract.EXTRA_DETAIL), certificate, found,
        )
    }

    fun remove(id: String): MailResult = call(MailContract.METHOD_REMOVE, id).result()

    private fun keyExtras(key: ItemKey) = Bundle().apply {
        putStringArray(HubContract.EXTRA_ACCOUNTS, arrayOf(key.accountId))
        putStringArray(HubContract.EXTRA_KEYS, arrayOf(key.sourceKey))
    }

    /** The mail [key] names, read from its server; or why not. */
    fun open(key: ItemKey): Pair<Opened?, MailResult> {
        val b = call(MailContract.METHOD_OPEN, extras = keyExtras(key))
        val result = b.result()
        if (b == null || result != MailResult.OK) return null to result
        fun list(extra: String) = b.getStringArray(extra).orEmpty().map(::addr)
        return Opened(
            from = b.getString(MailContract.EXTRA_FROM)?.let(::addr),
            to = list(MailContract.EXTRA_TO),
            cc = list(MailContract.EXTRA_CC),
            replyTo = list(MailContract.EXTRA_REPLY_TO),
            subject = b.getString(MailContract.EXTRA_SUBJECT).orEmpty(),
            date = b.getLong(MailContract.EXTRA_DATE),
            messageId = b.getString(MailContract.EXTRA_MESSAGE_ID),
            references = b.getStringArray(MailContract.EXTRA_REFERENCES).orEmpty().toList(),
            text = b.getString(MailContract.EXTRA_TEXT).orEmpty(),
            own = b.getString(MailContract.EXTRA_OWN).orEmpty(),
        ) to result
    }

    private fun addr(packed: String) = MailContract.parse(packed).let { (email, name) -> Addr(email, name) }

    /** Mark Read, Mark Unread or Delete of one mail, in the Hub and on its server (HubContract). */
    fun write(method: String, key: ItemKey) {
        call(method, extras = keyExtras(key))
    }

    /** Sent by apps/hub over SMTP before it answers, [signature] under the text: [d] must be [MailDraft.sendable]. */
    fun send(d: MailDraft, signature: String?): MailResult {
        val pack = { text: String -> parseAddresses(text).orEmpty().map { MailContract.address(it.email, it.name) }.toTypedArray() }
        val x = Bundle().apply {
            putString(MailContract.EXTRA_ACCOUNT, d.account)
            putStringArray(MailContract.EXTRA_TO, pack(d.to))
            putStringArray(MailContract.EXTRA_CC, pack(d.cc))
            putString(MailContract.EXTRA_SUBJECT, d.subject)
            putString(MailContract.EXTRA_TEXT, signed(d.text, signature))
            putString(MailContract.EXTRA_IN_REPLY_TO, d.inReplyTo)
            putStringArray(MailContract.EXTRA_REFERENCES, d.references.toTypedArray())
        }
        return call(MailContract.METHOD_SEND, extras = x).result()
    }
}

/**
 * The drafts kept on the phone, one file each in the no-backup directory: what
 * "Changes made!" > Save keeps (sim-47, C), and every mail before it is sent, so that a send that
 * fails, or a process that dies, never loses it. Shown in the inbox (AndroidInboxReader). Blocking:
 * MessagesGraph's thread only; [changed] runs after each write.
 */
class Drafts(context: Context, private val changed: () -> Unit) {
    private val dir = File(context.noBackupFilesDir, "drafts")

    fun all(): List<MailDraft> = dir.listFiles().orEmpty().mapNotNull { f -> runCatching { draftOf(f.readText()) }.getOrNull() }

    fun get(id: String): MailDraft? = file(id)?.takeIf { it.exists() }?.let { runCatching { draftOf(it.readText()) }.getOrNull() }

    /** Kept under its id, a new one for a new draft; returns it as kept. */
    fun save(d: MailDraft): MailDraft {
        val kept = d.copy(id = d.id ?: UUID.randomUUID().toString(), savedAt = System.currentTimeMillis())
        dir.mkdirs()
        val f = file(kept.id!!)!!
        val tmp = File(dir, "${f.name}.tmp")
        tmp.writeText(kept.encoded())
        check(tmp.renameTo(f)) { "draft not kept" }
        changed()
        return kept
    }

    fun delete(id: String) {
        if (file(id)?.delete() == true) changed()
    }

    // Ids are this class's own UUIDs: anything else names no file.
    private fun file(id: String): File? = if (id.matches(Regex("[0-9a-f-]{36}"))) File(dir, "$id.draft") else null
}

/** The contacts with a mail address, for To and Cc (READ_CONTACTS, granted by tools/install.sh); nothing when refused. */
fun emailContacts(context: Context): List<EmailContact> = try {
    val byContact = LinkedHashMap<Long, Pair<String, MutableList<String>>>()
    context.contentResolver.query(
        Email.CONTENT_URI, arrayOf(Email.CONTACT_ID, Email.DISPLAY_NAME_PRIMARY, Email.ADDRESS),
        null, null, "${Email.SORT_KEY_PRIMARY}, ${Email.IS_SUPER_PRIMARY} DESC, ${Email._ID}",
    )?.use { c ->
        while (c.moveToNext()) {
            val address = c.getString(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            byContact.getOrPut(c.getLong(0)) { c.getString(1).orEmpty() to ArrayList() }.second += address
        }
    }
    byContact.values.map { (name, list) -> EmailContact(name, list) }
} catch (e: RuntimeException) {
    Log.w(TAG, "contacts unreadable: ${e.javaClass.name}")
    emptyList()
}
