package dev.bold7.mail

import com.fsck.k9.mail.CertificateChainException
import com.fsck.k9.mail.ssl.TrustedSocketFactory
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import net.thunderbird.core.common.net.tls.CertificateBlocklist

/**
 * TLS for IMAP and SMTP: the system's CAs only (no "accept this certificate anyway", unlike
 * Thunderbird's LocalKeyStore), the server name checked against the certificate, and
 * Thunderbird's blocklist of compromised keys. Thunderbird checks the name in its own trust
 * manager with httpclient5's verifier; the protocol code hands over an unconnected socket, so the
 * check stays in the trust manager here too, with [hostnameMatches] instead of httpclient5.
 */
class TlsSocketFactory(private val system: X509TrustManager = systemTrustManager()) : TrustedSocketFactory {
    override fun createSocket(socket: Socket?, host: String, port: Int, clientCertificateAlias: String?): Socket {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(HostTrustManager(host, system)), null) }
        val ssl = if (socket == null) {
            context.socketFactory.createSocket()
        } else {
            context.socketFactory.createSocket(socket, host, port, true)
        } as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
        return ssl
    }

    private class HostTrustManager(private val host: String, private val system: X509TrustManager) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                CertificateBlocklist().isBlockedSpkiSha256(chain)
                system.checkServerTrusted(chain, authType)
                val names = chain[0].subjectAlternativeNames.orEmpty().filter { it[0] == DNS_NAME }.map { it[1] as String }
                if (names.none { hostnameMatches(host, it) }) throw CertificateException("Certificate is not for $host")
            } catch (e: CertificateException) {
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
