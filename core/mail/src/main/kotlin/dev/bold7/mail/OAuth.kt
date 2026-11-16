package dev.bold7.mail

import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.oauth.OAuth2TokenProvider
import com.squareup.moshi.Moshi
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// OAuth 2.0 for an installed app (RFC 8252): the system's browser, a redirect to a
// loopback address this process listens on, PKCE (RFC 7636) and a state. No WebView ever sees the
// password, no library: these few functions, tested against a local token endpoint.

/** An authorization server, its two endpoints and what the mail servers ask of a token. */
data class OAuthServer(
    val id: String,
    val authorize: String,
    val token: String,
    val scope: String,
    /** The redirect's host: Google documents 127.0.0.1, Microsoft registers http://localhost (port ignored). */
    val loopbackHost: String,
    val extra: Map<String, String> = emptyMap(),
)

object OAuthServers {
    // developers.google.com/identity/protocols/oauth2/native-app (Desktop app client, loopback).
    // Offline access and consent: a refresh token comes with every sign-in.
    val GOOGLE = OAuthServer(
        "google", "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
        "https://mail.google.com/", "127.0.0.1", mapOf("access_type" to "offline", "prompt" to "consent"),
    )

    // learn.microsoft.com, "Authenticate an IMAP, POP or SMTP connection using OAuth": personal
    // accounts (outlook.com, hotmail, live), the consumers tenant.
    val MICROSOFT = OAuthServer(
        "microsoft", "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
        "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
        "https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send offline_access", "localhost",
        mapOf("prompt" to "select_account"),
    )

    /** The server of MailProviders' [providerId], null when its accounts log in by app password only. */
    fun forProvider(providerId: String): OAuthServer? = when (providerId) {
        MailProviders.GMAIL.id -> GOOGLE
        MailProviders.OUTLOOK.id -> MICROSOFT
        else -> null
    }

    fun byId(id: String): OAuthServer? = listOf(GOOGLE, MICROSOFT).firstOrNull { it.id == id }
}

/** Bold7's registration with a server, from local.properties (never in git); a Desktop client of Google has a secret, Microsoft's public client none. */
data class OAuthClient(val id: String, val secret: String? = null) {
    override fun toString() = "OAuthClient($id)"
}

/** What a server answered: the access token, when it expires (epoch ms), and a refresh token when it gave one. */
data class OAuthTokens(val access: String, val expiresAt: Long, val refresh: String?) {
    override fun toString() = "OAuthTokens(expiresAt=$expiresAt, refresh=${refresh != null})"
}

/** A refusal by the token endpoint: its error code only, never its description (it may name the account). */
class OAuthException(val error: String) : IOException("OAuth: $error")

/** 32 random bytes, base64url: a PKCE verifier (43 characters, RFC 7636 §4.1), or a state. */
fun newSecret(random: SecureRandom): String = b64(ByteArray(32).also(random::nextBytes))

/** S256: base64url of the verifier's SHA-256. */
fun pkceChallenge(verifier: String): String = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

private fun formOf(params: Map<String, String>) = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

/** The page the browser opens: the code asked for with PKCE (S256) and [state], the account given as a hint. Never the secret. */
fun authorizationUrl(server: OAuthServer, client: OAuthClient, redirect: String, challenge: String, state: String, loginHint: String): String =
    server.authorize + "?" + formOf(
        linkedMapOf(
            "response_type" to "code",
            "client_id" to client.id,
            "redirect_uri" to redirect,
            "scope" to server.scope,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "login_hint" to loginHint,
        ) + server.extra,
    )

sealed interface Redirect {
    data class Code(val code: String) : Redirect {
        override fun toString() = "Code"
    }

    /** The user, or the server, said no ([error], RFC 6749 §4.1.2.1). */
    data class Denied(val error: String) : Redirect
}

/**
 * The browser's request line, if it is this flow's redirect: a GET whose state is [state], with a
 * code or an error. Anything else (a favicon, another state, another app's request) is null.
 */
fun parseRedirect(requestLine: String, state: String): Redirect? {
    val parts = requestLine.split(' ')
    if (parts.size != 3 || parts[0] != "GET" || !parts[2].startsWith("HTTP/")) return null
    val uri = runCatching { URI(parts[1]) }.getOrNull() ?: return null
    val q = uri.rawQuery?.split('&').orEmpty().mapNotNull {
        val kv = it.split('=', limit = 2)
        if (kv.size == 2) URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8") else null
    }.toMap()
    // Simplification: compared plainly, the state is not a secret once in the browser's address bar.
    if (q["state"] != state) return null
    q["code"]?.takeIf { it.isNotEmpty() }?.let { return Redirect.Code(it) }
    return q["error"]?.let(Redirect::Denied)
}

/**
 * The redirect's listener: a socket on the loopback address only, an ephemeral port, until
 * [close]. Another app on the phone could reach it, never take its port: what it sends is
 * ignored unless it carries the flow's state, and a code is worth nothing without the verifier.
 */
class Loopback(host: String) : Closeable {
    private val socket = ServerSocket(0, 4, InetAddress.getLoopbackAddress())

    /** The redirect_uri: [host] (127.0.0.1 or localhost, as the server registers it) and the port. */
    val redirect = "http://$host:${socket.localPort}/"

    /** The redirect of the flow [state], or null after [timeoutMs] or once closed. Blocking. */
    fun await(state: String, timeoutMs: Long): Redirect? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val client = try {
                socket.soTimeout = left.toInt()
                socket.accept()
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: SocketException) {
                return null // closed
            }
            client.use {
                it.soTimeout = 5_000
                val line = try {
                    readLine(it.getInputStream())
                } catch (e: IOException) {
                    null
                }
                val redirect = line?.let { l -> parseRedirect(l, state) }
                val (status, page) = if (redirect != null) "200 OK" to PAGE else "404 Not Found" to ""
                runCatching {
                    it.getOutputStream().write(
                        ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
                            "Content-Length: ${page.toByteArray().size}\r\nConnection: close\r\n\r\n$page").toByteArray(),
                    )
                }
                if (redirect != null) return redirect
            }
        }
    }

    override fun close() = socket.close()

    private companion object {
        // D: OS 7 had no such page; Escape closes the browser's tab and Email Setup goes on.
        const val PAGE = "<!doctype html><meta name=viewport content=\"width=device-width\"><title>Bold7</title>" +
            "<p style=\"font:27px sans-serif;margin:14px\">Signed in. Press Escape to return to Email Setup.</p>"

        /** The request line, 8 KB at most: a redirect is much shorter. */
        fun readLine(input: java.io.InputStream): String? {
            val b = StringBuilder()
            while (b.length < 8192) {
                val c = input.read()
                if (c < 0) return null
                if (c == '\n'.code) return b.toString().trimEnd('\r')
                b.append(c.toChar())
            }
            return null
        }
    }
}

/** Posts a form to a token endpoint: the HTTP status and the body. Blocking. */
fun interface TokenPost {
    fun post(url: String, form: Map<String, String>): Pair<Int, String>
}

/** HttpsURLConnection, the system's trust and host name check; http only to this phone's loopback (the tests' endpoint). */
object HttpTokenPost : TokenPost {
    override fun post(url: String, form: Map<String, String>): Pair<Int, String> {
        val u = URL(url)
        require(u.protocol == "https" || u.host == "127.0.0.1" || u.host == "localhost") { "Token endpoint over https only" }
        val c = u.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 30_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("Accept", "application/json")
            c.outputStream.use { it.write(formOf(form).toByteArray()) }
            val status = c.responseCode
            val body = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            return status to body
        } finally {
            c.disconnect()
        }
    }
}

private val json by lazy { Moshi.Builder().build().adapter(Map::class.java) }

private fun tokens(answer: Pair<Int, String>, now: Long): OAuthTokens {
    val map = runCatching { json.fromJson(answer.second) }.getOrNull().orEmpty()
    val access = map["access_token"] as? String
    if (answer.first !in 200..299 || access == null) throw OAuthException(map["error"] as? String ?: "http_${answer.first}")
    val expiresIn = (map["expires_in"] as? Number)?.toLong() ?: 3600
    return OAuthTokens(access, now + expiresIn * 1000, map["refresh_token"] as? String)
}

private fun clientForm(client: OAuthClient) = linkedMapOf("client_id" to client.id).apply { client.secret?.let { put("client_secret", it) } }

/** The code exchanged for tokens, with the verifier and the same redirect_uri (RFC 7636 §4.5). */
fun exchangeCode(server: OAuthServer, client: OAuthClient, code: String, verifier: String, redirect: String, post: TokenPost, now: () -> Long): OAuthTokens =
    tokens(post.post(server.token, clientForm(client) + mapOf("grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect)), now())

/**
 * The access token for IMAP and SMTP (XOAUTH2), refreshed before the connection when it is
 * missing or within a minute of its end, or after a server refused it. One per account, shared by
 * its connections. A new refresh token (Microsoft rotates them) goes to [onRotated], to be sealed
 * again. A refresh token revoked or expired (invalid_grant: 7 days for a Google project in
 * Testing) is a refused login: the push stops until the user signs in again.
 */
class OAuthTokenSource(
    private val server: OAuthServer,
    private val client: OAuthClient,
    @Volatile private var refresh: String,
    private val post: TokenPost,
    private val now: () -> Long,
    /** The tokens of the sign-in itself, used before any refresh. */
    initial: OAuthTokens? = null,
    private val onRotated: (String) -> Unit,
) : OAuth2TokenProvider {
    private var current: OAuthTokens? = initial

    override val usernames: Set<String> = emptySet()

    @Synchronized
    override fun getToken(timeoutMillis: Long): String {
        current?.takeIf { now() < it.expiresAt - 60_000 }?.let { return it.access }
        val t = try {
            tokens(post.post(server.token, clientForm(client) + mapOf("grant_type" to "refresh_token", "refresh_token" to refresh)), now())
        } catch (e: OAuthException) {
            if (e.error == "invalid_grant" || e.error == "invalid_client" || e.error == "unauthorized_client") {
                throw AuthenticationFailedException("OAuth refresh refused: ${e.error}")
            }
            throw e
        }
        t.refresh?.takeIf { it != refresh }?.let {
            refresh = it
            onRotated(it)
        }
        current = t
        return t.access
    }

    @Synchronized
    override fun invalidateToken() {
        current = null
    }
}
