package dev.bold7.mail

import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings

/**
 * TLS always: implicit (IMAPS 993, SMTPS 465, RFC 8314), or STARTTLS required where a provider
 * offers nothing else for SMTP (Outlook, iCloud: 587): the connection fails rather than go on in
 * clear when the server does not offer it. [tls] off is for the local test server only, hence
 * the internal constructor: the public API has no way to a cleartext connection.
 */
data class Endpoint internal constructor(val host: String, val port: Int, val tls: Boolean, val startTls: Boolean = false) {
    constructor(host: String, port: Int) : this(host, port, tls = true)

    companion object {
        fun startTls(host: String, port: Int) = Endpoint(host, port, tls = true, startTls = true)
    }
}

data class MailProvider(val id: String, val imap: Endpoint, val smtp: Endpoint)

object MailProviders {
    // Gmail's published settings: IMAP and SMTP over TLS.
    val GMAIL = MailProvider("gmail", Endpoint("imap.gmail.com", 993), Endpoint("smtp.gmail.com", 465))

    // Microsoft's and Apple's published settings: IMAP over TLS, SMTP on 587 with STARTTLS only.
    val OUTLOOK = MailProvider("outlook", Endpoint("outlook.office365.com", 993), Endpoint.startTls("smtp-mail.outlook.com", 587))
    val ICLOUD = MailProvider("icloud", Endpoint("imap.mail.me.com", 993), Endpoint.startTls("smtp.mail.me.com", 587))

    /** Servers the user typed in, for a domain none of these knows. */
    const val CUSTOM = "custom"

    private val byDomain = mapOf(
        "gmail.com" to GMAIL, "googlemail.com" to GMAIL,
        "outlook.com" to OUTLOOK, "outlook.fr" to OUTLOOK, "hotmail.com" to OUTLOOK, "hotmail.fr" to OUTLOOK,
        "live.com" to OUTLOOK, "live.fr" to OUTLOOK, "msn.com" to OUTLOOK,
        "icloud.com" to ICLOUD, "me.com" to ICLOUD, "mac.com" to ICLOUD,
    )

    fun forAddress(address: String): MailProvider? {
        val parts = address.trim().lowercase().split('@')
        return if (parts.size == 2 && parts[0].isNotEmpty()) byDomain[parts[1]] else null
    }

    fun byId(id: String): MailProvider? = byDomain.values.firstOrNull { it.id == id }

    /** Servers typed for an unknown domain: implicit TLS, or STARTTLS on 587 and 143 (their usual ports, D). */
    fun custom(imapHost: String, imapPort: Int, smtpHost: String, smtpPort: Int) =
        MailProvider(CUSTOM, endpoint(imapHost, imapPort, starting = 143), endpoint(smtpHost, smtpPort, starting = 587))

    private fun endpoint(host: String, port: Int, starting: Int) =
        if (port == starting) Endpoint.startTls(host.trim(), port) else Endpoint(host.trim(), port)
}

/**
 * OS 7's signature (S: BlackBerry Internet Service added it to every mail sent from a 9900; the
 * guide 7.1 p. 90, B, sets it per account, "added to your email after you send it").
 */
const val SIGNATURE = "Sent from my BlackBerry\u00AE smartphone"

/**
 * One mail account. Logged in with an app password or, signed in through the
 * browser ([oauth], an OAuthServers id), by XOAUTH2: [password] is then the refresh
 * token, sealed the same way, never sent to a mail server. [signature]: Use Auto Signature, null
 * when off (guide 7.1 p. 90, B).
 */
data class MailAccount(
    val address: String,
    val password: String,
    val provider: MailProvider,
    val oauth: String? = null,
    val signature: String? = SIGNATURE,
) {
    fun imapSettings() = settings("imap", provider.imap)

    fun smtpSettings() = settings("smtp", provider.smtp)

    private fun settings(type: String, endpoint: Endpoint) = ServerSettings(
        type = type,
        host = endpoint.host,
        port = endpoint.port,
        connectionSecurity = when {
            !endpoint.tls -> ConnectionSecurity.NONE
            endpoint.startTls -> ConnectionSecurity.STARTTLS_REQUIRED
            else -> ConnectionSecurity.SSL_TLS_REQUIRED
        },
        authenticationType = if (oauth != null) AuthType.XOAUTH2 else AuthType.PLAIN,
        username = address,
        password = password.takeIf { oauth == null },
        clientCertificateAlias = null,
    )

    override fun toString() = "MailAccount($address, ${provider.id}${oauth?.let { ", $it" }.orEmpty()})"
}
