package io.github.brenobarreto.posthog.api

import io.github.brenobarreto.posthog.auth.AuthManager
import io.github.brenobarreto.posthog.auth.FakeHttpServer
import io.github.brenobarreto.posthog.auth.MemoryCredentialStore
import io.github.brenobarreto.posthog.auth.OAuthTokens
import io.github.brenobarreto.posthog.auth.StubOAuthClient
import io.github.brenobarreto.posthog.auth.UserInfo
import io.github.brenobarreto.posthog.auth.apiKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PostHogClientTest {
    private val server = FakeHttpServer()

    @After
    fun tearDown() = server.close()

    private fun page(results: String, next: String? = null) =
        """{"count":0,"next":${next?.let { "\"$it\"" } ?: "null"},"previous":null,"results":[$results]}"""

    private fun flagJson(id: Int, key: String) = """{"id":$id,"key":"$key","name":"","active":true,"deleted":false,"filters":{}}"""

    private fun client(auth: AuthManager) = PostHogClient(auth)

    @Test
    fun `fetches flags with a bearer token`() = runBlocking {
        server.on("/api/projects/7/feature_flags/", 200, page(flagJson(1, "a") + "," + flagJson(2, "b")))
        val flags = client(apiKeyManager(server.baseUrl, "phx_secret")).featureFlags(7)

        assertEquals(listOf("a", "b"), flags.map { it.key })
        val request = server.requests.single()
        assertEquals("Bearer phx_secret", request.headers["authorization"])
        assertEquals("limit=100", request.query)
    }

    @Test
    fun `follows pagination by path and never leaves the account host`() = runBlocking {
        server.on("/api/projects/7/feature_flags/") { request ->
            if (request.query == "limit=100") {
                200 to page(flagJson(1, "a"), next = "https://evil.example.test/api/projects/7/feature_flags/?limit=100&offset=1")
            } else {
                200 to page(flagJson(2, "b"))
            }
        }
        val flags = client(apiKeyManager(server.baseUrl)).featureFlags(7)

        assertEquals(listOf("a", "b"), flags.map { it.key })
        assertEquals(listOf("limit=100", "limit=100&offset=1"), server.requests.map { it.query })
    }

    @Test
    fun `gives up on endless pagination`() {
        server.on("/api/projects/7/feature_flags/") { 200 to page(flagJson(1, "a"), next = "${server.baseUrl}/api/projects/7/feature_flags/?offset=1") }
        val e = assertThrows(PostHogApiException::class.java) { runBlocking { client(apiKeyManager(server.baseUrl)).featureFlags(7) } }
        assertTrue(e.message!!.contains("pages"))
    }

    @Test
    fun `fetches experiments`() = runBlocking {
        server.on(
            "/api/projects/7/experiments/", 200,
            page("""{"id":1,"name":"Pricing","start_date":"2026-01-01","end_date":null,"feature_flag_key":"pricing"}"""),
        )
        val experiments = client(apiKeyManager(server.baseUrl)).experiments(7)
        assertEquals(ExperimentStatus.RUNNING, experiments.single().status)
    }

    @Test
    fun `lists projects from the account when the token is not scoped`() = runBlocking {
        server.on("/api/projects/", 200, page("""{"id":1,"name":"Web"},{"id":2,"name":"Mobile"}"""))
        assertEquals(listOf(Project(1, "Web"), Project(2, "Mobile")), client(apiKeyManager(server.baseUrl)).projects())
    }

    @Test
    fun `a scoped token only sees its own projects`() = runBlocking {
        server.on("/api/projects/5/", 200, """{"id":5,"name":"Scoped"}""")
        val oauth = StubOAuthClient(OAuthTokens("pha_1", "phr_1", Long.MAX_VALUE, server.baseUrl, emptyList(), scopedTeams = listOf(5)))
        val auth = AuthManager(MemoryCredentialStore(), oauth, { _, _ -> UserInfo(null, null) })
        auth.signInWithOAuth { }

        assertEquals(listOf(Project(5, "Scoped")), client(auth).projects())
        assertFalse(server.requests.any { it.path == "/api/projects/" })
    }

    @Test
    fun `a 401 renews the token once and retries`() = runBlocking {
        server.on("/api/projects/7/feature_flags/") { request ->
            if (request.headers["authorization"] == "Bearer new") 200 to page(flagJson(1, "a")) else 401 to "{}"
        }
        val oauth = StubOAuthClient(
            OAuthTokens("old", "phr_1", Long.MAX_VALUE, server.baseUrl, emptyList()),
            refreshResult = { OAuthTokens("new", "phr_2", Long.MAX_VALUE, server.baseUrl, emptyList()) },
        )
        val auth = AuthManager(MemoryCredentialStore(), oauth, { _, _ -> UserInfo(null, null) })
        auth.signInWithOAuth { }

        assertEquals(listOf("a"), client(auth).featureFlags(7).map { it.key })
        assertEquals(listOf("Bearer old", "Bearer new"), server.requests.map { it.headers["authorization"] })
        assertEquals(1, oauth.refreshCalls)
    }

    @Test
    fun `a persistent 401 is reported as an auth failure`() {
        server.on("/api/projects/7/feature_flags/", 401, "{}")
        val e = assertThrows(PostHogApiException::class.java) { runBlocking { client(apiKeyManager(server.baseUrl)).featureFlags(7) } }
        assertEquals(401, e.status)
        assertTrue(e.isAuthFailure)
    }

    @Test
    fun `other failures keep their status and a readable message`() {
        fun failure(status: Int): PostHogApiException {
            server.on("/api/projects/7/feature_flags/", status, "{}")
            return assertThrows(PostHogApiException::class.java) { runBlocking { client(apiKeyManager(server.baseUrl)).featureFlags(7) } }
        }
        assertTrue(failure(403).isAuthFailure)
        assertTrue(failure(404).message!!.contains("selected project"))
        assertTrue(failure(429).message!!.contains("rate limiting"))
        assertFalse(failure(500).isAuthFailure)
    }

    @Test
    fun `a garbled body is an error, not a crash`() {
        server.on("/api/projects/7/feature_flags/", 200, "<html>maintenance</html>")
        val e = assertThrows(PostHogApiException::class.java) { runBlocking { client(apiKeyManager(server.baseUrl)).featureFlags(7) } }
        assertEquals(0, e.status)
    }

    @Test
    fun `being signed out is an auth failure and makes no request`() {
        val auth = AuthManager(MemoryCredentialStore(), StubOAuthClient(), { _, _ -> UserInfo(null, null) })
        val e = assertThrows(PostHogApiException::class.java) { runBlocking { client(auth).featureFlags(7) } }
        assertTrue(e.isAuthFailure)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `an unreachable host has status zero`() {
        val auth = runBlocking { apiKeyManager(server.baseUrl) }
        server.close()
        val e = assertThrows(PostHogApiException::class.java) { runBlocking { client(auth).featureFlags(7) } }
        assertEquals(0, e.status)
    }
}
