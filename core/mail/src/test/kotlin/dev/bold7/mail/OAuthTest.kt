package dev.bold7.mail

import com.fsck.k9.mail.AuthenticationFailedException
import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OAuthTest {
    private val client = OAuthClient("client-id.apps.example", "desktop-secret")

    // A token endpoint in this JVM, on localhost: no test reaches Google or Microsoft.
    private val posted = CopyOnWriteArrayList<Map<String, String>>()
    private var answer: Pair<Int, String> = 200 to """{"access_token":"ya29.access","expires_in":3599,"refresh_token":"1//refresh","token_type":"Bearer"}"""
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/token") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            posted += form(body)
            val (status, json) = answer
            exchange.sendResponseHeaders(status, json.length.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        start()
    }
    private val server = OAuthServers.GOOGLE.copy(token = "http://127.0.0.1:${http.address.port}/token")

    @AfterTest
    fun stop() = http.stop(0)

    private fun form(body: String) = body.split('&').filter { it.isNotEmpty() }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun query(url: String) = form(URI(url).rawQuery)

    @Test
    fun `the code challenge is the S256 of the verifier (RFC 7636, appendix B)`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `a verifier and a state are 43 unreserved characters, never twice the same`() {
        val random = SecureRandom()
        val a = newSecret(random)
        assertEquals(43, a.length)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' }, a)
        assertNotEquals(a, newSecret(random))
    }

    @Test
    fun `the authorization asks for the code with PKCE, the state and the account, never the secret`() {
        val url = authorizationUrl(OAuthServers.GOOGLE, client, "http://127.0.0.1:4711/", "challenge", "state-1", "alice@gmail.com")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), url)
        val q = query(url)
        assertEquals("code", q["response_type"])
        assertEquals("client-id.apps.example", q["client_id"])
        assertEquals("http://127.0.0.1:4711/", q["redirect_uri"])
        assertEquals("https://mail.google.com/", q["scope"])
        assertEquals("challenge", q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("state-1", q["state"])
        assertEquals("alice@gmail.com", q["login_hint"])
        // Google gives a refresh token only for offline access, and again only on consent.
        assertEquals("offline", q["access_type"])
        assertEquals("consent", q["prompt"])
        assertFalse("desktop-secret" in url)
    }

    @Test
    fun `microsoft is asked for IMAP, SMTP and a refresh token, personal accounts`() {
        val q = query(authorizationUrl(OAuthServers.MICROSOFT, OAuthClient("ms-id"), "http://localhost:4711/", "c", "s", "alice@outlook.com"))
        assertEquals(setOf("https://outlook.office.com/IMAP.AccessAsUser.All", "https://outlook.office.com/SMTP.Send", "offline_access"), q.getValue("scope").split(' ').toSet())
        assertTrue(OAuthServers.MICROSOFT.authorize.startsWith("https://login.microsoftonline.com/consumers/"))
    }

    @Test
    fun `each provider has its server, the others none`() {
        assertSame(OAuthServers.GOOGLE, OAuthServers.forProvider(MailProviders.GMAIL.id))
        assertSame(OAuthServers.MICROSOFT, OAuthServers.forProvider(MailProviders.OUTLOOK.id))
        assertNull(OAuthServers.forProvider(MailProviders.ICLOUD.id))
        assertNull(OAuthServers.forProvider(MailProviders.CUSTOM))
        for (s in listOf(OAuthServers.GOOGLE, OAuthServers.MICROSOFT)) {
            assertTrue(s.authorize.startsWith("https://") && s.token.startsWith("https://"), s.id)
            assertSame(s, OAuthServers.byId(s.id))
        }
    }

    @Test
    fun `microsoft's tenant is personal accounts for now, work and school accounts waiting behind a constant`() {
        assertEquals("consumers", OAuthServers.MICROSOFT_TENANT)
        assertTrue(OAuthServers.MICROSOFT.token.startsWith("https://login.microsoftonline.com/consumers/"))
        // Microsoft 365 needs the common tenant: no browser sign-in for it while consumers is set.
        assertNull(OAuthServers.forProvider(MailProviders.MICROSOFT_365.id))
        assertEquals("https://login.microsoftonline.com/common/oauth2/v2.0/token", microsoftServer("common").token)
    }

    @Test
    fun `only the redirect of this flow is taken`() {
        assertEquals(Redirect.Code("4/abc"), parseRedirect("GET /?state=s1&code=4%2Fabc&scope=x HTTP/1.1", "s1"))
        assertEquals(Redirect.Denied("access_denied"), parseRedirect("GET /?error=access_denied&state=s1 HTTP/1.1", "s1"))
        // Another state is another flow, or a page of another app: ignored.
        assertNull(parseRedirect("GET /?state=s2&code=4%2Fabc HTTP/1.1", "s1"))
        assertNull(parseRedirect("GET /?code=4%2Fabc HTTP/1.1", "s1"))
        assertNull(parseRedirect("GET /favicon.ico HTTP/1.1", "s1"))
        assertNull(parseRedirect("POST /?state=s1&code=x HTTP/1.1", "s1"))
        assertNull(parseRedirect("garbage", "s1"))
    }

    @Test
    fun `the loopback hears the browser, ignores what is not its redirect, and answers a page`() {
        Loopback("127.0.0.1").use { loop ->
            assertTrue(loop.redirect.matches(Regex("http://127\\.0\\.0\\.1:\\d+/")), loop.redirect)
            val port = URI(loop.redirect).port
            thread {
                browse(port, "/favicon.ico")
                browse(port, "/?state=other&code=stolen")
                assertEquals(200, browse(port, "/?state=s1&code=good"))
            }
            assertEquals(Redirect.Code("good"), loop.await("s1", 5_000))
        }
    }

    @Test
    fun `the loopback gives up after its time, and once closed`() {
        val loop = Loopback("127.0.0.1")
        assertNull(loop.await("s1", 200))
        thread {
            Thread.sleep(100)
            loop.close()
        }
        assertNull(loop.await("s1", 5_000))
    }

    private fun browse(port: Int, path: String): Int = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).run {
        try {
            responseCode
        } finally {
            disconnect()
        }
    }

    @Test
    fun `the code is exchanged with its verifier and the same redirect`() {
        val tokens = exchangeCode(server, client, "4/abc", "verifier", "http://127.0.0.1:4711/", HttpTokenPost, now = { 1_000L })
        assertEquals("ya29.access", tokens.access)
        assertEquals("1//refresh", tokens.refresh)
        assertEquals(1_000L + 3_599_000L, tokens.expiresAt)
        val p = posted.single()
        assertEquals("authorization_code", p["grant_type"])
        assertEquals("4/abc", p["code"])
        assertEquals("verifier", p["code_verifier"])
        assertEquals("http://127.0.0.1:4711/", p["redirect_uri"])
        assertEquals("client-id.apps.example", p["client_id"])
        assertEquals("desktop-secret", p["client_secret"])
    }

    @Test
    fun `a client without secret sends none (Microsoft's public clients)`() {
        exchangeCode(server, OAuthClient("ms-id"), "c", "v", "http://localhost:1/", HttpTokenPost, now = { 0L })
        assertFalse("client_secret" in posted.single())
    }

    @Test
    fun `a refused exchange names the error only, never the server's description`() {
        answer = 400 to """{"error":"invalid_grant","error_description":"Bad Request for alice@gmail.com"}"""
        val e = assertFailsWith<OAuthException> { exchangeCode(server, client, "c", "v", "r", HttpTokenPost, now = { 0L }) }
        assertEquals("invalid_grant", e.error)
        assertFalse("alice" in e.toString())
    }

    @Test
    fun `tokens never show in their text`() {
        val t = OAuthTokens("ya29.secret-access", 0, "1//secret-refresh")
        assertFalse("secret" in t.toString())
        assertFalse("desktop-secret" in client.toString())
    }

    @Test
    fun `the access token is refreshed once, then kept until a minute before it expires`() {
        var now = 0L
        val rotated = mutableListOf<String>()
        answer = 200 to """{"access_token":"a1","expires_in":3600}"""
        val source = OAuthTokenSource(server, client, "1//refresh", HttpTokenPost, { now }) { rotated += it }
        assertEquals("a1", source.getToken(30_000))
        assertEquals("a1", source.getToken(30_000))
        assertEquals(1, posted.size)
        assertEquals("refresh_token", posted[0]["grant_type"])
        assertEquals("1//refresh", posted[0]["refresh_token"])

        answer = 200 to """{"access_token":"a2","expires_in":3600}"""
        now = 3_540_001L
        assertEquals("a2", source.getToken(30_000))
        assertEquals(2, posted.size)
        assertTrue(rotated.isEmpty(), "Google keeps its refresh token: nothing to seal again")
    }

    @Test
    fun `a token the server refused is fetched again, and a new refresh token is sealed again`() {
        val rotated = mutableListOf<String>()
        answer = 200 to """{"access_token":"a1","expires_in":3600}"""
        val source = OAuthTokenSource(server, OAuthClient("ms-id"), "old", HttpTokenPost, { 0L }) { rotated += it }
        source.getToken(30_000)
        source.invalidateToken()
        // Microsoft rotates its refresh tokens: the new one replaces the sealed one.
        answer = 200 to """{"access_token":"a2","expires_in":3600,"refresh_token":"new"}"""
        assertEquals("a2", source.getToken(30_000))
        assertEquals(listOf("new"), rotated)
        answer = 200 to """{"access_token":"a3","expires_in":3600}"""
        source.invalidateToken()
        source.getToken(30_000)
        assertEquals("new", posted.last()["refresh_token"])
    }

    @Test
    fun `a revoked or expired refresh token is a refused login, which stops the push`() {
        answer = 400 to """{"error":"invalid_grant"}"""
        val source = OAuthTokenSource(server, client, "1//refresh", HttpTokenPost, { 0L }) {}
        assertIs<AuthenticationFailedException>(assertFailsWith<AuthenticationFailedException> { source.getToken(30_000) })
    }

    @Test
    fun `nothing but https leaves for a real server`() {
        assertFailsWith<IllegalArgumentException> { HttpTokenPost.post("http://oauth2.googleapis.com/token", emptyMap()) }
    }

    @Test
    fun `the access token of the sign-in serves before any refresh`() {
        val source = OAuthTokenSource(server, client, "1//refresh", HttpTokenPost, { 0L }, initial = OAuthTokens("ya29.first", 3_600_000, "1//refresh")) {}
        assertEquals("ya29.first", source.getToken(30_000))
        assertTrue(posted.isEmpty())
    }
}
