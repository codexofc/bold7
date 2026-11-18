package dev.bold7.mail

import com.fsck.k9.mail.CertificateChainException
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import net.thunderbird.core.common.net.tls.CertificateBlocklist

/**
 * A certificate the user accepted for one server ([host] and [port]) of one account, after Email
 * Setup showed its names and its SHA-256 fingerprint ([sha256], [fingerprint]'s form): that very
 * certificate, on that server only. Never for a known provider, never global; deleting the account
 * forgets it (the 9900's Server Exceptions, guide 7.1 p. 172, B, were the browser's).
 */
data class Pin(val host: String, val port: Int, val sha256: String) {
    /** "host port sha256", as MailContract carries it. */
    override fun toString() = "$host $port $sha256"

    companion object {
        fun parse(s: String): Pin? = s.split(' ').takeIf { it.size == 3 }?.let { (h, p, f) ->
            p.toIntOrNull()?.takeIf { it in 1..65535 && h.isNotEmpty() && f.matches(FINGERPRINT) }?.let { Pin(h, it, f) }
        }

        private val FINGERPRINT = Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")
    }
}

/** A certificate's SHA-256 over its DER bytes, "AB:CD:…" (32 bytes), as browsers show it. */
fun fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

/**
 * TLS for IMAP and SMTP: the system's CAs, the server name checked against the certificate, and
 * Thunderbird's blocklist of compromised keys. A certificate that fails passes only when it is
 * the one [pins] holds for this host and port (the user accepted it, [Pin]); a blocklisted key
 * never does. Thunderbird checks the name in its own trust
 * manager with httpclient5's verifier; the protocol code hands over an unconnected socket, so the
 * check stays in the trust manager here too, with [hostnameMatches] instead of httpclient5.
 */
class TlsSocketFactory(
    private val system: X509TrustManager = systemTrustManager(),
    private val pins: List<Pin> = emptyList(),
) : TrustedSocketFactory {
    override fun createSocket(socket: Socket?, host: String, port: Int, clientCertificateAlias: String?): Socket {
        val pinned = pins.filter { it.host.equals(host, ignoreCase = true) && it.port == port }.map { it.sha256 }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(HostTrustManager(host, system, pinned)), null) }
        val ssl = if (socket == null) {
            context.socketFactory.createSocket()
        } else {
            context.socketFactory.createSocket(socket, host, port, true)
        } as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
        return ssl
    }

    private class HostTrustManager(private val host: String, private val system: X509TrustManager, private val pinned: List<String>) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                CertificateBlocklist().isBlockedSpkiSha256(chain)
            } catch (e: CertificateException) {
                throw CertificateChainException(e.message, chain, e)
            }
            try {
                system.checkServerTrusted(chain, authType)
                val names = chain[0].subjectAlternativeNames.orEmpty().filter { it[0] == DNS_NAME }.map { it[1] as String }
                if (names.none { hostnameMatches(host, it) }) throw CertificateException("Certificate is not for $host")
            } catch (e: CertificateException) {
                // The certificate the user accepted for this server, exactly: nothing else, not even a renewal.
                if (chain.isNotEmpty() && fingerprint(chain[0]) in pinned) return
                throw CertificateChainException(e.message, chain, e)
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            system.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    private companion object {
        const val DNS_NAME = 2

        fun systemTrustManager(): X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}

/**
 * RFC 6125 §6.4: case-insensitive, a wildcard only as the whole leftmost label, standing for
 * exactly one label, and never directly under a top-level domain.
 */
fun hostnameMatches(host: String, pattern: String): Boolean {
    val h = host.lowercase().removeSuffix(".")
    val p = pattern.lowercase().removeSuffix(".")
    if (!p.startsWith("*.")) return h == p
    val suffix = p.substring(1)
    if (suffix.count { it == '.' } < 2 || '*' in suffix) return false
    val label = h.removeSuffix(suffix)
    return h.endsWith(suffix) && label.isNotEmpty() && '.' !in label
}
