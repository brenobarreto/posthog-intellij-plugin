package io.github.brenobarreto.posthog.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthManagerTest {
    private class MemoryStore(var value: String? = null) : CredentialStore {
        override fun load() = value
        override fun save(value: String?) { this.value = value }
    }

    private class FakeOAuth(
        var signInResult: OAuthTokens? = null,
        var refreshResult: () -> OAuthTokens = { error("unexpected refresh") },
    ) : OAuthClient {
        var refreshCalls = 0
        override suspend fun signIn(openBrowser: (String) -> Unit): OAuthTokens {
            openBrowser("https://auth.test/authorize")
            return signInResult!!
        }
        override suspend fun refresh(tokens: OAuthTokens): OAuthTokens {
            refreshCalls++
            return refreshResult()
        }
    }

    private var now = 10_000_000L
    private val store = MemoryStore()
    private val oauth = FakeOAuth()
    private val changes = mutableListOf<AuthCredentials?>()
    private var userLookup: (String, String) -> UserInfo = { _, _ -> UserInfo("dev@example.test", null) }

    private fun manager(store: CredentialStore = this.store) = AuthManager(
        store, oauth, { host, token -> userLookup(host, token) }, clock = { now }, onChange = { changes += it },
    )

    private fun tokens(access: String = "pha_1", expiresIn: Long = 3_600_000) =
        OAuthTokens(access, "phr_1", now + expiresIn, "https://eu.posthog.com", listOf("feature_flag:read"))

    @Test
    fun `api key sign-in verifies the key, stores it and notifies`() = runBlocking {
        var checked: Pair<String, String>? = null
        userLookup = { host, token -> checked = host to token; UserInfo("dev@example.test", null) }
        val m = manager()
        m.signInWithApiKey(" https://eu.posthog.com/ ", " phx_key ")

        assertEquals("https://eu.posthog.com" to "phx_key", checked)
        val current = m.current()!!
        assertEquals(AuthMethod.API_KEY, current.method)
        assertEquals("dev@example.test", current.accountLabel)
        assertEquals("phx_key", m.accessToken())
        assertEquals(listOf(current), changes)
        assertTrue(store.value!!.contains("phx_key"))
    }

    @Test
    fun `a rejected api key leaves the existing session untouched`() = runBlocking {
        val m = manager()
        m.signInWithApiKey("https://us.posthog.com", "phx_good")
        userLookup = { _, _ -> throw AuthException("PostHog rejected the credentials (HTTP 401)") }

        assertThrows(AuthException::class.java) { runBlocking { m.signInWithApiKey("https://us.posthog.com", "phx_bad") } }
        assertEquals("phx_good", m.accessToken())
        assertEquals(1, changes.size)
    }

    @Test
    fun `api key input is validated before any request`() {
        userLookup = { _, _ -> error("should not be called") }
        assertThrows(AuthException::class.java) { runBlocking { manager().signInWithApiKey("not a url", "phx_key") } }
        assertThrows(AuthException::class.java) { runBlocking { manager().signInWithApiKey("https://us.posthog.com", "  ") } }
    }

    @Test
    fun `oauth sign-in stores the session and survives a restart`() = runBlocking {
        oauth.signInResult = tokens()
        var opened: String? = null
        manager().signInWithOAuth { opened = it }

        assertEquals("https://auth.test/authorize", opened)
        val restarted = manager()
        val current = restarted.current()!!
        assertEquals(AuthMethod.OAUTH, current.method)
        assertEquals("https://eu.posthog.com", current.host)
        assertEquals("phr_1", current.refreshToken)
        assertEquals("pha_1", restarted.accessToken())
    }

    @Test
    fun `oauth sign-in still succeeds when the account lookup fails`() = runBlocking {
        oauth.signInResult = tokens()
        userLookup = { _, _ -> throw AuthException("offline") }
        val m = manager()
        m.signInWithOAuth { }
        assertNull(m.current()!!.accountLabel)
    }

    @Test
    fun `a token that is not close to expiring is used as is`() = runBlocking {
        oauth.signInResult = tokens(expiresIn = 3_600_000)
        val m = manager()
        m.signInWithOAuth { }
        assertEquals("pha_1", m.accessToken())
        assertEquals(0, oauth.refreshCalls)
    }

    @Test
    fun `an expiring token is refreshed, stored and announced`() = runBlocking {
        oauth.signInResult = tokens(expiresIn = 60_000)
        oauth.refreshResult = { tokens("pha_2").copy(refreshToken = "phr_2") }
        val m = manager()
        m.signInWithOAuth { }
        changes.clear()

        assertEquals("pha_2", m.accessToken())
        assertEquals("pha_2", m.accessToken())
        assertEquals(1, oauth.refreshCalls)
        assertEquals(1, changes.size)
        assertEquals("phr_2", AuthCredentials.fromJson(store.value!!)!!.refreshToken)
    }

    @Test
    fun `a lost session signs the user out`() = runBlocking {
        oauth.signInResult = tokens(expiresIn = 60_000)
        oauth.refreshResult = { throw AuthException("expired", reauthRequired = true) }
        val m = manager()
        m.signInWithOAuth { }
        changes.clear()

        assertThrows(AuthException::class.java) { runBlocking { m.accessToken() } }
        assertNull(m.current())
        assertNull(m.accessToken())
        assertNull(store.value)
        assertEquals(listOf<AuthCredentials?>(null), changes)
    }

    @Test
    fun `a temporary refresh failure keeps the session`() = runBlocking {
        oauth.signInResult = tokens(expiresIn = 60_000)
        oauth.refreshResult = { throw AuthException("offline") }
        val m = manager()
        m.signInWithOAuth { }

        assertThrows(AuthException::class.java) { runBlocking { m.accessToken() } }
        assertEquals("pha_1", m.current()!!.accessToken)
    }

    @Test
    fun `sign-out clears the store and notifies`() = runBlocking {
        val m = manager()
        m.signInWithApiKey("https://us.posthog.com", "phx_key")
        m.signOut()
        assertNull(m.current())
        assertNull(store.value)
        assertNull(changes.last())
    }

    @Test
    fun `unreadable stored data means signed out`() = runBlocking {
        assertNull(manager(MemoryStore("{not json")).current())
        assertNull(manager(MemoryStore("""{"method":"API_KEY"}""")).current())
        assertNull(manager(MemoryStore(null)).accessToken())
    }

    @Test
    fun `hosts are normalised and validated`() {
        assertEquals("https://eu.posthog.com", PostHogHosts.normalize(" https://eu.posthog.com/ "))
        assertEquals("http://localhost:8010", PostHogHosts.normalize("http://localhost:8010"))
        listOf("", "posthog.com", "ftp://posthog.com", "https://posthog.com/app", "https://").forEach {
            assertThrows(it, AuthException::class.java) { PostHogHosts.normalize(it) }
        }
    }
}
