package dev.bold7.mail

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.CertificateValidationException
import com.fsck.k9.mail.MissingCapabilityException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException

/** Why the setup's check of a server failed. */
enum class Failure {
    /** The login name or the password refused. */
    AUTH,

    /** A certificate the phone does not trust, or for another name. */
    CERTIFICATE,

    /** No such server name. */
    HOST_UNKNOWN,

    /** Nothing listens on that port, or no route to it. */
    PORT_CLOSED,

    /** No answer in time. */
    TIMEOUT,

    /** STARTTLS asked for, not offered: the connection stops rather than go on in clear. */
    NO_STARTTLS,

    /** TLS failed before any certificate: most often implicit TLS on a STARTTLS port. */
    TLS,

    /** The server offers no login by password that Bold7 speaks (PLAIN, LOGIN). */
    NO_PASSWORD_LOGIN,

    /** Anything else on the way. */
    NETWORK,
}

/** A certificate as Email Setup shows it before the user accepts it or not. */
data class CertificateInfo(val sha256: String, val names: List<String>, val issuer: String, val selfSigned: Boolean, val expired: Boolean)

/** A failed check: what, on which [server], the server's own words ([detail]) for a refused login. */
data class CheckError(val failure: Failure, val server: Endpoint? = null, val detail: String? = null, val certificate: CertificateInfo? = null)

/**
 * [e], thrown by the check of [server], described. Pure: the exception's chain is all it reads.
 * The server's words are cleaned (no control character, 200 characters at most): they are shown to
 * the user, never logged.
 */
fun diagnose(e: Throwable, server: Endpoint?, now: Long = System.currentTimeMillis()): CheckError {
    val chain = generateSequence(e) { it.cause }.toList()
    chain.filterIsInstance<AuthenticationFailedException>().firstOrNull()?.let { return CheckError(Failure.AUTH, server, it.messageFromServer?.let(::cleaned)) }
    chain.filterIsInstance<CertificateValidationException>().firstOrNull()?.let { c ->
        return CheckError(Failure.CERTIFICATE, server, certificate = c.certificateChain.firstOrNull()?.let { info(it, now) })
    }
    chain.filterIsInstance<MissingCapabilityException>().firstOrNull()?.let {
        return CheckError(if (it.capabilityName.equals("STARTTLS", ignoreCase = true)) Failure.NO_STARTTLS else Failure.NO_PASSWORD_LOGIN, server)
    }
    val failure = when {
        chain.any { it is UnknownHostException } -> Failure.HOST_UNKNOWN
        chain.any { it is ConnectException || it is NoRouteToHostException } -> Failure.PORT_CLOSED
        chain.any { it is SocketTimeoutException } -> Failure.TIMEOUT
        chain.any { it is SSLException } -> Failure.TLS
        else -> Failure.NETWORK
    }
    return CheckError(failure, server)
}

private fun cleaned(text: String): String? =
    text.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("").replace(Regex(" {2,}"), " ").trim().take(200).ifEmpty { null }

/** The names a certificate is for: its DNS subjectAltNames, else its subject's CN. */
private fun info(cert: X509Certificate, now: Long): CertificateInfo {
    val sans = runCatching { cert.subjectAlternativeNames.orEmpty().filter { it[0] == 2 }.map { it[1] as String } }.getOrDefault(emptyList())
    val subject = cert.subjectX500Principal.name
    return CertificateInfo(
        sha256 = fingerprint(cert),
        names = sans.ifEmpty { listOfNotNull(cn(subject)) },
        issuer = cn(cert.issuerX500Principal.name) ?: cert.issuerX500Principal.name,
        selfSigned = cert.issuerX500Principal == cert.subjectX500Principal,
        expired = now > cert.notAfter.time || now < cert.notBefore.time,
    )
}

private fun cn(dn: String): String? = Regex("(?:^|,)\\s*CN=((?:\\\\,|[^,])+)").find(dn)?.groupValues?.get(1)?.replace("\\,", ",")
