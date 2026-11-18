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

/** How a provider's accounts log in from another app. */
enum class Login {
    /** The account's password, or an app password where the provider asks for one. */
    PASSWORD,

    /** OAuth only, through the browser: the provider takes no password from another app. */
    BROWSER,

    /** Only through a program of the provider's on a computer (Proton Mail Bridge): never from this phone. */
    BRIDGE,
}

/**
 * A provider's servers. [id] names it for the vault and for Email Setup's notices; [login] is not
 * stored (the vault rebuilds it from [id]).
 */
data class MailProvider(val id: String, val imap: Endpoint, val smtp: Endpoint, val login: Login = Login.PASSWORD)

/**
 * [address] trimmed, its domain lowercased without a trailing dot and in ASCII (IDN, RFC 5891),
 * its local part as typed; null when it is not one address or its domain could bend a URL.
 */
fun normalizedAddress(address: String): String? {
    val a = address.trim()
    val at = a.lastIndexOf('@')
    if (at <= 0) return null
    val local = a.substring(0, at)
    if (local.any { it.isWhitespace() || it == '@' }) return null
    val domain = try {
        java.net.IDN.toASCII(a.substring(at + 1).trimEnd('.'), java.net.IDN.USE_STD3_ASCII_RULES).lowercase()
    } catch (e: IllegalArgumentException) {
        return null
    }
    return if (domain.matches(DOMAIN)) "$local@$domain" else null
}

/** [address]'s domain, as [normalizedAddress] gives it. */
fun domainOf(address: String): String? = normalizedAddress(address)?.substringAfterLast('@')

private val DOMAIN = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")

/**
 * The providers known by their domains, with the servers and the way of logging in each one
 * publishes (docs/MAIL.md §11 gives the page of each). Every server over TLS: implicit where
 * offered, STARTTLS required where the provider offers nothing else for SMTP.
 */
object MailProviders {
    // Gmail's published settings: IMAP and SMTP over TLS.
    val GMAIL = MailProvider("gmail", Endpoint("imap.gmail.com", 993), Endpoint("smtp.gmail.com", 465))

    // Microsoft's and Apple's published settings: IMAP over TLS, SMTP on 587 with STARTTLS only.
    // Outlook.com takes no password from another app since September 16, 2024: OAuth only.
    val OUTLOOK = MailProvider("outlook", Endpoint("outlook.office365.com", 993), Endpoint.startTls("smtp-mail.outlook.com", 587), Login.BROWSER)
    val ICLOUD = MailProvider("icloud", Endpoint("imap.mail.me.com", 993), Endpoint.startTls("smtp.mail.me.com", 587))

    /** A domain whose mail Microsoft 365 receives (its MX): a work or school account, OAuth only (learn.microsoft.com). */
    val MICROSOFT_365 = MailProvider("microsoft365", Endpoint("outlook.office365.com", 993), Endpoint.startTls("smtp.office365.com", 587), Login.BROWSER)

    val YAHOO = MailProvider("yahoo", Endpoint("imap.mail.yahoo.com", 993), Endpoint("smtp.mail.yahoo.com", 465))
    val AOL = MailProvider("aol", Endpoint("imap.aol.com", 993), Endpoint("smtp.aol.com", 465))
    val GMX = MailProvider("gmx", Endpoint("imap.gmx.net", 993), Endpoint("mail.gmx.net", 465))
    val GMX_COM = MailProvider("gmx", Endpoint("imap.gmx.com", 993), Endpoint("mail.gmx.com", 465))
    val WEB_DE = MailProvider("webde", Endpoint("imap.web.de", 993), Endpoint("smtp.web.de", 465))
    val MAIL_COM = MailProvider("mailcom", Endpoint("imap.mail.com", 993), Endpoint("smtp.mail.com", 465))
    val ZOHO = MailProvider("zoho", Endpoint("imap.zoho.com", 993), Endpoint("smtp.zoho.com", 465))
    val YANDEX = MailProvider("yandex", Endpoint("imap.yandex.com", 993), Endpoint("smtp.yandex.com", 465))

    /** Proton's IMAP and SMTP are its Bridge's, on the user's computer: never reached from here. */
    val PROTON = MailProvider("proton", Endpoint("127.0.0.1", 1143), Endpoint("127.0.0.1", 1025), Login.BRIDGE)

    val ORANGE = MailProvider("orange", Endpoint("imap.orange.fr", 993), Endpoint("smtp.orange.fr", 465))
    val FREE = MailProvider("free", Endpoint("imap.free.fr", 993), Endpoint("smtp.free.fr", 465))
    val SFR = MailProvider("sfr", Endpoint("imap.sfr.fr", 993), Endpoint("smtp.sfr.fr", 465))
    val LAPOSTE = MailProvider("laposte", Endpoint("imap.laposte.net", 993), Endpoint("smtp.laposte.net", 465))
    val BBOX = MailProvider("bbox", Endpoint("imap.bbox.fr", 993), Endpoint("smtp.bbox.fr", 465))

    /** Servers the user typed in, or found for a domain none of these knows. */
    const val CUSTOM = "custom"

    // The domains each publishes; Outlook's, Yahoo's, AOL's and GMX's lists are Thunderbird's ISPDB's
    // (autoconfig.thunderbird.net, read in November 2026), less the domains of other companies it lists.
    private fun words(s: String) = s.trim().split(Regex("\\s+"))

    private val byDomain: Map<String, MailProvider> = buildMap {
        fun add(p: MailProvider, domains: String) = words(domains).forEach { put(it, p) }
        add(GMAIL, "gmail.com googlemail.com")
        add(
            OUTLOOK,
            """
            hotmail.com live.com msn.com outlook.com windowslive.com outlook.at outlook.be outlook.cl outlook.cz outlook.de
            outlook.dk outlook.es outlook.fr outlook.hu outlook.ie outlook.in outlook.it outlook.jp outlook.kr outlook.lv
            outlook.my outlook.ph outlook.pt outlook.sa outlook.sg outlook.sk outlook.co.id outlook.co.il outlook.co.th
            outlook.com.ar outlook.com.au outlook.com.br outlook.com.gr outlook.com.tr outlook.com.vn hotmail.be hotmail.ca
            hotmail.cl hotmail.cz hotmail.de hotmail.dk hotmail.es hotmail.fi hotmail.fr hotmail.gr hotmail.hu hotmail.it
            hotmail.lt hotmail.lv hotmail.my hotmail.nl hotmail.no hotmail.ph hotmail.rs hotmail.se hotmail.sg hotmail.sk
            hotmail.co.id hotmail.co.il hotmail.co.in hotmail.co.jp hotmail.co.kr hotmail.co.th hotmail.co.uk hotmail.co.za
            hotmail.com.ar hotmail.com.au hotmail.com.br hotmail.com.hk hotmail.com.tr hotmail.com.tw hotmail.com.vn live.at
            live.be live.ca live.cl live.cn live.de live.dk live.fi live.fr live.hk live.ie live.in live.it live.jp live.nl
            live.no live.ru live.se live.co.jp live.co.kr live.co.uk live.co.za live.com.ar live.com.au live.com.mx
            live.com.my live.com.ph live.com.pt live.com.sg livemail.tw
            """,
        )
        add(ICLOUD, "icloud.com me.com mac.com")
        add(
            YAHOO,
            "yahoo.com yahoo.ca yahoo.de yahoo.it yahoo.fr yahoo.es yahoo.se yahoo.co.in yahoo.co.uk yahoo.co.nz " +
                "yahoo.com.au yahoo.com.ar yahoo.com.br yahoo.com.mx ymail.com myyahoo.com rocketmail.com",
        )
        add(
            AOL,
            "aol.com aim.com netscape.net netscape.com compuserve.com cs.com wmconnect.com aol.de aol.it aol.fr aol.es " +
                "aol.se aol.co.uk aol.co.nz aol.com.au aol.com.ar aol.com.br aol.com.mx",
        )
        add(GMX, "gmx.net gmx.de gmx.at gmx.ch gmx.eu gmx.biz gmx.org gmx.info")
        add(
            GMX_COM,
            "gmx.com gmx.tm gmx.us gmx.co.uk gmx.es gmx.fr gmx.ca gmx.cn gmx.co.in gmx.com.br gmx.com.my gmx.hk gmx.ie " +
                "gmx.ph gmx.pt gmx.ru gmx.se gmx.sg gmx.tw gmx.com.tr gmx.it gmx.li",
        )
        add(WEB_DE, "web.de")
        // mail.com has some two hundred domains: its best known here, the others found by the ISPDB.
        add(
            MAIL_COM,
            "mail.com mail.org email.com post.com usa.com accountant.com consultant.com dr.com engineer.com cheerful.com " +
                "techie.com linuxmail.org europe.com london.com myself.com iname.com writeme.com",
        )
        add(ZOHO, "zoho.com zohomail.com")
        add(YANDEX, "yandex.ru yandex.com yandex.net yandex.by yandex.kz yandex.ua ya.ru narod.ru")
        add(PROTON, "proton.me protonmail.com protonmail.ch pm.me")
        add(ORANGE, "orange.fr wanadoo.fr")
        add(FREE, "free.fr")
        add(SFR, "sfr.fr neuf.fr club-internet.fr")
        add(LAPOSTE, "laposte.net")
        add(BBOX, "bbox.fr")
    }

    /** Every known provider, each once. */
    val all: List<MailProvider> get() = (byDomain.values + MICROSOFT_365).distinct()

    fun forAddress(address: String): MailProvider? = domainOf(address)?.let(byDomain::get)

    fun byId(id: String): MailProvider? = all.firstOrNull { it.id == id }

    /** STARTTLS is usual on 143 and 587, implicit TLS elsewhere (D). */
    fun usualStartTls(port: Int) = port == 143 || port == 587

    /** Servers typed for an unknown domain: implicit TLS, or STARTTLS on 587 and 143 (their usual ports, D). */
    fun custom(imapHost: String, imapPort: Int, smtpHost: String, smtpPort: Int) = custom(imapHost, imapPort, null, smtpHost, smtpPort, null)

    /** Servers typed with their security: STARTTLS when [startTlsImap] or [startTlsSmtp], or when null on the port's usual one. Never clear. */
    fun custom(imapHost: String, imapPort: Int, startTlsImap: Boolean?, smtpHost: String, smtpPort: Int, startTlsSmtp: Boolean?) =
        MailProvider(CUSTOM, endpoint(imapHost, imapPort, startTlsImap), endpoint(smtpHost, smtpPort, startTlsSmtp))

    private fun endpoint(host: String, port: Int, startTls: Boolean?) =
        if (startTls ?: usualStartTls(port)) Endpoint.startTls(host.trim(), port) else Endpoint(host.trim(), port)
}

/**
 * OS 7's signature (S: BlackBerry Internet Service added it to every mail sent from a 9900; the
 * guide 7.1 p. 90, B, sets it per account, "added to your email after you send it").
 */
const val SIGNATURE = "Sent from my BlackBerry\u00AE smartphone"

/**
 * One mail account. Logged in with its password (or an app password) or, signed in through the
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
    /** The login name of both servers: the address, unless the user typed another (typed servers only). */
    val user: String = address,
    /** Certificates the user accepted for this account's servers (typed servers only), each for its server alone. */
    val pins: List<Pin> = emptyList(),
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
        username = user,
        password = password.takeIf { oauth == null },
        clientCertificateAlias = null,
    )

    override fun toString() = "MailAccount($address, ${provider.id}${oauth?.let { ", $it" }.orEmpty()})"
}
