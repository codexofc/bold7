package dev.bold7.mail

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.MissingCapabilityException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.thunderbird.core.common.exception.MessagingException

class DiagnosisTest {
    private val imap = Endpoint("mail.example.org", 993)

    private fun wrapped(e: Throwable) = MessagingException("Error while testing settings", e)

    @Test
    fun `each failure says what went wrong, and on which server`() {
        assertEquals(CheckError(Failure.HOST_UNKNOWN, imap), diagnose(wrapped(UnknownHostException("mail.example.org")), imap))
        assertEquals(Failure.PORT_CLOSED, diagnose(wrapped(ConnectException("Connection refused")), imap).failure)
        assertEquals(Failure.TIMEOUT, diagnose(wrapped(SocketTimeoutException()), imap).failure)
        assertEquals(Failure.NO_STARTTLS, diagnose(MissingCapabilityException("STARTTLS"), imap).failure)
        assertEquals(Failure.NO_PASSWORD_LOGIN, diagnose(MissingCapabilityException("AUTH PLAIN"), imap).failure)
        assertEquals(Failure.TLS, diagnose(wrapped(SSLHandshakeException("Unsupported or unrecognized SSL message")), imap).failure)
        assertEquals(Failure.NETWORK, diagnose(wrapped(java.io.IOException("reset")), imap).failure)
        assertEquals(imap, diagnose(wrapped(java.io.IOException()), imap).server)
    }

    @Test
    fun `a refused login keeps the server's own words, cleaned`() {
        val e = AuthenticationFailedException("refused", null, "[AUTHENTICATIONFAILED]\r\nInvalid\u0000 credentials\t (Failure)")
        val error = diagnose(wrapped(e), imap)
        assertEquals(Failure.AUTH, error.failure)
        assertEquals("[AUTHENTICATIONFAILED] Invalid credentials (Failure)", error.detail)
        assertEquals(200, diagnose(AuthenticationFailedException("x", null, "a".repeat(5000)), imap).detail!!.length)
        assertNull(diagnose(AuthenticationFailedException("x"), imap).detail)
    }
}
