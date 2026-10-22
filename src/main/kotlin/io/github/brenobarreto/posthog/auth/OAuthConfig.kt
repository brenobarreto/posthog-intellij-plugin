package io.github.brenobarreto.posthog.auth

object OAuthConfig {
    /** Client ID Metadata Document: the client ID is the URL of the JSON file published from `docs/oauth/client.json`. */
    const val CLIENT_ID = "https://brenobarreto.github.io/posthog-intellij-plugin/oauth/client.json"

    const val DISCOVERY_URL = "https://oauth.posthog.com/.well-known/oauth-authorization-server"

    /** Must stay within the scopes declared in `docs/oauth/client.json`. */
    val SCOPES = listOf(
        "feature_flag:read",
        "experiment:read",
        "project:read",
        "organization:read",
        "user:read",
    )

    const val CALLBACK_PATH = "/callback"
    const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1000L
    const val REFRESH_BUFFER_MS = 5 * 60 * 1000L
}

object PostHogHosts {
    const val US = "https://us.posthog.com"
    const val EU = "https://eu.posthog.com"

    /** Trims a user-entered host to `scheme://host[:port]` form, or throws [AuthException]. */
    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        val uri = try {
            java.net.URI(trimmed)
        } catch (e: java.net.URISyntaxException) {
            throw invalid(e)
        }
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty() || !uri.path.isNullOrEmpty()) throw invalid(null)
        return trimmed
    }

    private fun invalid(cause: Throwable?) =
        AuthException("Enter a valid host URL, for example https://posthog.example.com", cause = cause)
}
