package dev.bold7.mail

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

// The servers of a domain no table knows, looked up when the user presses Next in Email Setup
// (the only moment it is asked for), as Thunderbird does: its ISPDB, then the domain's own
// autoconfig file, then the domain's MX. Over https only, a few seconds each, nothing kept.

/** What a domain's configuration gives: its servers, and the login name for [address]. */
data class Discovered(val provider: MailProvider, val user: String)

/** A GET over https: the body, or null (not found, too large, no network). Blocking. */
fun interface ConfigFetch {
    fun get(url: String): String?
}

/**
 * HttpsURLConnection with the system's trust and host name check: https only, no redirect
 * followed (a configuration that moved elsewhere is not the domain's), 5 s to connect and to read,
 * 64 KB at most. No cookie, no address sent: the URL holds the domain only.
 */
object HttpConfigFetch : ConfigFetch {
    private const val MAX = 64 * 1024

    override fun get(url: String): String? {
        val u = URL(url)
        if (u.protocol != "https") return null
        val c = u.openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("Accept", "application/xml, text/xml")
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { it.readNBytes(MAX + 1) }
            if (bytes.size > MAX) null else bytes.decodeToString()
        } catch (e: IOException) {
            null
        } finally {
            c.disconnect()
        }
    }
}

/**
 * The servers of [address]'s domain: Thunderbird's ISPDB, then autoconfig.<domain>, then its MX
 * ([mx], the hosts by preference): Google's, Microsoft's or Proton's as theirs, any other through
 * the ISPDB of its base domain. Null when none answers: the user types the servers.
 */
fun discover(address: String, fetch: ConfigFetch, mx: (String) -> List<String>): Discovered? {
    val normalized = normalizedAddress(address) ?: return null
    val domain = normalized.substringAfterLast('@')
    fun ispdb(d: String) = fetch.get("https://autoconfig.thunderbird.net/v1.1/$d")?.let { parseAutoconfig(it, normalized) }
    ispdb(domain)?.let { return it }
    fetch.get("https://autoconfig.$domain/mail/config-v1.1.xml")?.let { parseAutoconfig(it, normalized) }?.let { return it }
    val hosts = mx(domain)
    mxProvider(hosts)?.let { return Discovered(it, normalized) }
    val base = hosts.firstOrNull()?.split('.')?.takeLast(2)?.joinToString(".")?.takeIf { it != domain && it.contains('.') } ?: return null
    return ispdb(base)
}

/** Google Workspace, Microsoft 365 and Proton receive a domain's mail at these MX hosts (their published records). */
fun mxProvider(hosts: List<String>): MailProvider? {
    fun any(vararg suffixes: String) = hosts.any { h -> suffixes.any { h.lowercase().trimEnd('.').endsWith(it) } }
    return when {
        any(".google.com", ".googlemail.com") -> MailProviders.GMAIL
        any(".mail.protection.outlook.com") -> MailProviders.MICROSOFT_365
        any(".protonmail.ch") -> MailProviders.PROTON
        else -> null
    }
}

/**
 * A Thunderbird autoconfig file (version 1.1) for [address]: its first IMAP and its first SMTP
 * server over implicit TLS, else over STARTTLS, that take a password; servers in clear and
 * servers that take OAuth only are left out. Null when it has no such pair, or is no such file.
 * A DOCTYPE is refused before parsing: no entity of any kind is ever resolved.
 */
fun parseAutoconfig(xml: String, address: String): Discovered? {
    if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return null
    val local = address.substringBeforeLast('@')
    val domain = address.substringAfterLast('@')
    fun expand(s: String) = s.replace("%EMAILADDRESS%", address).replace("%EMAILLOCALPART%", local).replace("%EMAILDOMAIN%", domain).trim()
    val doc = try {
        DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }.newDocumentBuilder().parse(InputSource(xml.reader()))
    } catch (e: Exception) {
        return null
    }
    val provider = doc.documentElement?.takeIf { it.tagName == "clientConfig" }?.let { children(it, "emailProvider").firstOrNull() } ?: return null

    class Server(val host: String, val port: Int, val startTls: Boolean, val user: String)

    fun servers(tag: String, type: String): List<Server> = children(provider, tag).filter { it.getAttribute("type") == type }.mapNotNull { e ->
        val host = expand(text(e, "hostname") ?: return@mapNotNull null)
        val port = text(e, "port")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
        val auth = children(e, "authentication").map { it.textContent.trim() }
        if (auth.isNotEmpty() && auth.none { it == "password-cleartext" || it == "password-encrypted" }) return@mapNotNull null
        val startTls = when (text(e, "socketType")?.trim()) {
            "SSL" -> false
            "STARTTLS" -> true
            else -> return@mapNotNull null
        }
        if (host.isEmpty() || !host.matches(Regex("[A-Za-z0-9.-]+"))) return@mapNotNull null
        Server(host, port, startTls, expand(text(e, "username") ?: "%EMAILADDRESS%"))
    }.sortedBy { it.startTls }

    val imap = servers("incomingServer", "imap").firstOrNull() ?: return null
    val smtp = servers("outgoingServer", "smtp").firstOrNull() ?: return null
    return Discovered(MailProviders.custom(imap.host, imap.port, imap.startTls, smtp.host, smtp.port, smtp.startTls), imap.user.ifEmpty { address })
}

private fun children(e: Element, tag: String): List<Element> =
    (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

private fun text(e: Element, tag: String): String? = children(e, tag).firstOrNull()?.textContent

/**
 * The MX hosts of a raw DNS answer (RFC 1035 §4), by preference; empty for anything malformed.
 * Names may be compressed (§4.1.4): pointers are followed, a loop or a jump out of the message
 * ends the read.
 */
fun parseMx(answer: ByteArray): List<String> = try {
    fun u16(i: Int) = ((answer[i].toInt() and 0xff) shl 8) or (answer[i + 1].toInt() and 0xff)

    /** The name at [start] and the offset just after it in place. */
    fun name(start: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var i = start
        var end = -1
        var jumps = 0
        while (true) {
            val len = answer[i].toInt() and 0xff
            when {
                len == 0 -> {
                    if (end < 0) end = i + 1
                    return labels.joinToString(".") to end
                }
                len and 0xc0 == 0xc0 -> {
                    if (++jumps > 16) throw IndexOutOfBoundsException("loop")
                    if (end < 0) end = i + 2
                    i = ((len and 0x3f) shl 8) or (answer[i + 1].toInt() and 0xff)
                }
                else -> {
                    labels += String(answer, i + 1, len, Charsets.US_ASCII).also { if (i + 1 + len > answer.size) throw IndexOutOfBoundsException() }
                    i += 1 + len
                }
            }
        }
    }

    val questions = u16(4)
    val answers = u16(6)
    var at = 12
    repeat(questions) { at = name(at).second + 4 }
    val mx = ArrayList<Pair<Int, String>>()
    repeat(answers) {
        at = name(at).second
        val type = u16(at)
        val length = u16(at + 8)
        val data = at + 10
        if (type == 15) mx += u16(data) to name(data + 2).first.lowercase()
        at = data + length
    }
    mx.sortedBy { it.first }.map { it.second }.filter { it.isNotEmpty() }
} catch (e: IndexOutOfBoundsException) {
    emptyList()
}
