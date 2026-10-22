package io.github.brenobarreto.posthog.auth

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountApiTest {
    private val server = FakeHttpServer()

    @After
    fun tearDown() = server.close()

    @Test
    fun `reads the account and sends the token as a bearer header`() {
        server.on("/api/users/@me/", 200, """{"email":"dev@example.test","first_name":"Dev"}""")
        val user = runBlocking { AccountApi().fetchUser(server.baseUrl, "phx_secret") }

        assertEquals("dev@example.test", user.label)
        assertEquals("Bearer phx_secret", server.requests.single().headers["authorization"])
    }

    @Test
    fun `falls back to the first name, then a generic label`() {
        assertEquals("Dev", UserInfo(null, "Dev").label)
        assertEquals("PostHog user", UserInfo(null, null).label)
    }

    @Test
    fun `rejected credentials give a readable error`() {
        server.on("/api/users/@me/", 401, """{"detail":"Invalid token"}""")
        val e = assertThrows(AuthException::class.java) { runBlocking { AccountApi().fetchUser(server.baseUrl, "bad") } }
        assertEquals("PostHog rejected the credentials (HTTP 401)", e.message)
    }

    @Test
    fun `an unreachable host gives a readable error`() {
        val url = server.baseUrl
        server.close()
        assertThrows(AuthException::class.java) { runBlocking { AccountApi().fetchUser(url, "x") } }
    }
}
