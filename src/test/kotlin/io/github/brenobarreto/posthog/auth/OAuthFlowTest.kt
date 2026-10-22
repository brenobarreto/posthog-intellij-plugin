package io.github.brenobarreto.posthog.auth

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.concurrent.thread

class OAuthFlowTest {
    private val server = FakeHttpServer()
    private val now = 1_000_000L

    @After
    fun tearDown() = server.close()

    private fun flow(timeoutMs: Long = 5_000) = OAuthFlow(
        discoveryUrl = "${server.baseUrl}/.well-known/oauth-authorization-server",
        clientId = "https://example.test/client.json",
        scopes = listOf("a:read", "b:read"),
        callbackTimeoutMs = timeoutMs,
        clock = { now },
    )

    private fun stubServer(tokenStatus: Int = 200, tokenBody: String = TOKEN_BODY) {
        server.on(
            "/.well-known/oauth-authorization-server", 200,
            """{"authorization_endpoint":"${server.baseUrl}/oauth/authorize/","token_endpoint":"${server.baseUrl}/oauth/token/"}""",
        )
        server.on("/oauth/token/", tokenStatus, tokenBody)
    }

    /** Plays the user's browser: opens the redirect URI with [callback] derived from the authorization request. */
    private fun browser(
        seen: MutableList<Map<String, String>> = mutableListOf(),
        callback: (Map<String, String>) -> String,
    ): (String) -> Unit = { url ->
        val query = parseQuery(URI(url).rawQuery)
        seen += query
        thread {
            val target = query.getValue("redirect_uri") + "?" + callback(query)
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(target)).build(), HttpResponse.BodyHandlers.discarding())
        }
    }

    @Test
    fun `signs in with PKCE and returns tokens`() {
        stubServer()
        val seen = mutableListOf<Map<String, String>>()
        val tokens = runBlocking { flow().signIn(browser(seen) { "code=the-code&state=${it.getValue("state")}" }) }

        assertEquals("pha_1", tokens.accessToken)
        assertEquals("phr_1", tokens.refreshToken)
        assertEquals("https://eu.posthog.com", tokens.host)
        assertEquals(now + 3_600_000, tokens.expiresAtMs)
        assertEquals(listOf("a:read", "b:read"), tokens.scopes)

        val auth = seen.single()
        assertEquals("code", auth["response_type"])
        assertEquals("https://example.test/client.json", auth["client_id"])
        assertEquals("S256", auth["code_challenge_method"])
        assertEquals("a:read b:read", auth["scope"])
        assertTrue(auth.getValue("redirect_uri").matches(Regex("http://127\\.0\\.0\\.1:\\d+/callback")))

        val exchange = server.requests.single { it.path == "/oauth/token/" }.form
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("the-code", exchange["code"])
        assertEquals("https://example.test/client.json", exchange["client_id"])
        assertEquals(auth["redirect_uri"], exchange["redirect_uri"])
        assertEquals(auth["code_challenge"], Pkce.challenge(exchange.getValue("code_verifier")))
    }

    @Test
    fun `rejects a callback with the wrong state without exchanging the code`() {
        stubServer()
        val e = assertThrows(AuthException::class.java) {
            runBlocking { flow().signIn(browser { "code=the-code&state=forged" }) }
        }
        assertTrue(e.message!!.contains("did not match"))
        assertFalse(server.requests.any { it.path == "/oauth/token/" })
    }

    @Test
    fun `reports when the user denies access`() {
        stubServer()
        val e = assertThrows(AuthException::class.java) {
            runBlocking { flow().signIn(browser { "error=access_denied&state=${it.getValue("state")}" }) }
        }
        assertTrue(e.message!!.contains("access_denied"))
    }

    @Test
    fun `times out when the browser never comes back`() {
        stubServer()
        val e = assertThrows(AuthException::class.java) { runBlocking { flow(timeoutMs = 300).signIn { } } }
        assertTrue(e.message!!.contains("Timed out"))
    }

    @Test
    fun `fails when the token endpoint refuses the code`() {
        stubServer(tokenStatus = 400, tokenBody = """{"error":"invalid_grant"}""")
        val e = assertThrows(AuthException::class.java) {
            runBlocking { flow().signIn(browser { "code=bad&state=${it.getValue("state")}" }) }
        }
        assertTrue(e.message!!.contains("400"))
    }

    private val expiring = OAuthTokens("old", "phr_old", now, "https://us.posthog.com", listOf("a:read"))

    @Test
    fun `refresh keeps host and refresh token when the response omits them`() {
        stubServer(tokenBody = """{"access_token":"pha_2","expires_in":600}""")
        val fresh = runBlocking { flow().refresh(expiring) }

        assertEquals("pha_2", fresh.accessToken)
        assertEquals("phr_old", fresh.refreshToken)
        assertEquals("https://us.posthog.com", fresh.host)
        assertEquals(now + 600_000, fresh.expiresAtMs)
        val form = server.requests.single { it.path == "/oauth/token/" }.form
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("phr_old", form["refresh_token"])
    }

    @Test
    fun `refresh stores a rotated refresh token`() {
        stubServer(tokenBody = """{"access_token":"pha_2","refresh_token":"phr_new","expires_in":600}""")
        assertEquals("phr_new", runBlocking { flow().refresh(expiring) }.refreshToken)
    }

    @Test
    fun `refresh asks for a new sign-in when the refresh token is rejected`() {
        stubServer(tokenStatus = 400, tokenBody = """{"error":"invalid_grant"}""")
        val e = assertThrows(AuthException::class.java) { runBlocking { flow().refresh(expiring) } }
        assertTrue(e.reauthRequired)
    }

    @Test
    fun `a server error during refresh is not treated as a lost session`() {
        stubServer(tokenStatus = 503, tokenBody = "{}")
        val e = assertThrows(AuthException::class.java) { runBlocking { flow().refresh(expiring) } }
        assertFalse(e.reauthRequired)
    }

    private companion object {
        const val TOKEN_BODY =
            """{"access_token":"pha_1","refresh_token":"phr_1","expires_in":3600,"scope":"a:read b:read","posthog_base_url":"https://eu.posthog.com/"}"""
    }
}
