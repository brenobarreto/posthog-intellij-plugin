package io.github.brenobarreto.posthog.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Holds the signed-in account and hands out a valid token. The platform-free core of [AuthService],
 * so it can be unit tested with fakes.
 */
class AuthManager(
    private val store: CredentialStore,
    private val oauth: OAuthClient,
    private val fetchUser: suspend (host: String, token: String) -> UserInfo,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChange: (AuthCredentials?) -> Unit = {},
) {
    // Guards `cached` and token refreshes. Never held while waiting for the browser.
    private val lock = Mutex()
    private var loaded = false
    private var cached: AuthCredentials? = null

    suspend fun current(): AuthCredentials? = lock.withLock { loadLocked() }

    suspend fun signInWithOAuth(openBrowser: (String) -> Unit) {
        val tokens = oauth.signIn(openBrowser)
        val label = try {
            fetchUser(tokens.host, tokens.accessToken).label
        } catch (_: AuthException) {
            null // The label is cosmetic; the sign-in itself succeeded.
        }
        update(
            AuthCredentials(
                AuthMethod.OAUTH, tokens.host, tokens.accessToken, tokens.refreshToken,
                tokens.expiresAtMs, tokens.scopes, label,
            ),
        )
    }

    /** Verifies the key against PostHog first, so a wrong key never replaces a working session. */
    suspend fun signInWithApiKey(host: String, apiKey: String) {
        val normalizedHost = PostHogHosts.normalize(host)
        val key = apiKey.trim()
        if (key.isEmpty()) throw AuthException("Enter a personal API key")
        val user = fetchUser(normalizedHost, key)
        update(AuthCredentials(AuthMethod.API_KEY, normalizedHost, key, accountLabel = user.label))
    }

    suspend fun signOut() = update(null)

    /** A token that is valid now, refreshing an expiring OAuth session first; null when signed out. */
    suspend fun accessToken(): String? {
        var changed = false
        try {
            val credentials = lock.withLock {
                val c = loadLocked()
                if (c == null || c.method != AuthMethod.OAUTH || c.expiresAtMs - clock() > OAuthConfig.REFRESH_BUFFER_MS) {
                    return@withLock c
                }
                val fresh = try {
                    oauth.refresh(c.toTokens())
                } catch (e: AuthException) {
                    if (e.reauthRequired) {
                        persistLocked(null)
                        changed = true
                    }
                    throw e
                }
                val refreshed = c.copy(
                    accessToken = fresh.accessToken, refreshToken = fresh.refreshToken,
                    expiresAtMs = fresh.expiresAtMs, scopes = fresh.scopes,
                )
                persistLocked(refreshed)
                changed = true
                refreshed
            }
            return credentials?.accessToken
        } finally {
            if (changed) onChange(cached)
        }
    }

    private suspend fun update(credentials: AuthCredentials?) {
        lock.withLock { persistLocked(credentials) }
        onChange(credentials)
    }

    private suspend fun loadLocked(): AuthCredentials? {
        if (!loaded) {
            cached = withContext(Dispatchers.IO) { store.load() }?.let(AuthCredentials::fromJson)
            loaded = true
        }
        return cached
    }

    private suspend fun persistLocked(credentials: AuthCredentials?) {
        withContext(Dispatchers.IO) { store.save(credentials?.toJson()) }
        cached = credentials
        loaded = true
    }
}
