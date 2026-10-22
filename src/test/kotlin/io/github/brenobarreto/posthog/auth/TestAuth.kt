package io.github.brenobarreto.posthog.auth

internal class MemoryCredentialStore(var value: String? = null) : CredentialStore {
    override fun load() = value

    override fun save(value: String?) {
        this.value = value
    }
}

internal class StubOAuthClient(
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

/** An [AuthManager] that is already signed in with a personal API key for [host]. */
internal suspend fun apiKeyManager(host: String, key: String = "phx_key"): AuthManager =
    AuthManager(MemoryCredentialStore(), StubOAuthClient(), { _, _ -> UserInfo("dev@example.test", null) })
        .also { it.signInWithApiKey(host, key) }
