package io.github.brenobarreto.posthog.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    /** PostHog instance the token belongs to (US/EU routing), from `posthog_base_url`. */
    val host: String,
    val scopes: List<String>,
)

interface OAuthClient {
    /** Runs the browser sign-in; [openBrowser] receives the authorization URL. */
    suspend fun signIn(openBrowser: (String) -> Unit): OAuthTokens

    suspend fun refresh(tokens: OAuthTokens): OAuthTokens
}

/** Authorization code flow with PKCE and a loopback redirect, as a public client identified by a metadata document URL. */
class OAuthFlow(
    private val http: HttpClient = defaultHttpClient(),
    private val discoveryUrl: String = OAuthConfig.DISCOVERY_URL,
    private val clientId: String = OAuthConfig.CLIENT_ID,
    private val scopes: List<String> = OAuthConfig.SCOPES,
    private val callbackTimeoutMs: Long = OAuthConfig.CALLBACK_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : OAuthClient {

    override suspend fun signIn(openBrowser: (String) -> Unit): OAuthTokens = withContext(Dispatchers.IO) {
        val metadata = fetchMetadata()
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        LoopbackCallbackServer().use { server ->
            val authorizationUrl = authorizationUrl(
                metadata.authorizationEndpoint, server.redirectUri, Pkce.challenge(verifier), state,
            )
            openBrowser(authorizationUrl)
            val params = server.awaitCallback(callbackTimeoutMs) { ensureActive() }

            if (params["state"] != state) throw AuthException("Sign-in was rejected: the response did not match the request")
            params["error"]?.let { throw AuthException("PostHog denied the sign-in ($it)") }
            val code = params["code"] ?: throw AuthException("PostHog did not return an authorization code")

            val response = postForm(
                metadata.tokenEndpoint,
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "redirect_uri" to server.redirectUri,
                    "code_verifier" to verifier,
                ),
            )
            if (response.statusCode() !in 200..299) throw AuthException("Token exchange failed (HTTP ${response.statusCode()})")
            parseTokens(response.body(), fallbackHost = null)
        }
    }

    override suspend fun refresh(tokens: OAuthTokens): OAuthTokens = withContext(Dispatchers.IO) {
        val refreshToken = tokens.refreshToken
            ?: throw AuthException("The session cannot be renewed; sign in again", reauthRequired = true)
        val metadata = fetchMetadata()
        val response = postForm(
            metadata.tokenEndpoint,
            mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken),
        )
        when (response.statusCode()) {
            in 200..299 -> Unit
            400, 401 -> throw AuthException("The session expired; sign in again", reauthRequired = true)
            else -> throw AuthException("Token refresh failed (HTTP ${response.statusCode()})")
        }
        val fresh = parseTokens(response.body(), fallbackHost = tokens.host)
        fresh.copy(refreshToken = fresh.refreshToken ?: refreshToken, scopes = fresh.scopes.ifEmpty { tokens.scopes })
    }

    private class ServerMetadata(val authorizationEndpoint: String, val tokenEndpoint: String)

    private fun fetchMetadata(): ServerMetadata {
        val request = HttpRequest.newBuilder(URI.create(discoveryUrl)).timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json").GET().build()
        val response = send(request)
        if (response.statusCode() !in 200..299) throw AuthException("Could not load PostHog's sign-in settings (HTTP ${response.statusCode()})")
        val json = parseObject(response.body())
        return ServerMetadata(
            authorizationEndpoint = json.string("authorization_endpoint") ?: throw AuthException("PostHog's sign-in settings are incomplete"),
            tokenEndpoint = json.string("token_endpoint") ?: throw AuthException("PostHog's sign-in settings are incomplete"),
        )
    }

    private fun authorizationUrl(endpoint: String, redirectUri: String, challenge: String, state: String): String {
        val query = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "scope" to scopes.joinToString(" "),
        ).joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        return endpoint + (if ('?' in endpoint) "&" else "?") + query
    }

    private fun parseTokens(body: String, fallbackHost: String?): OAuthTokens {
        val json = parseObject(body)
        val host = json.string("posthog_base_url")?.trimEnd('/') ?: fallbackHost
            ?: throw AuthException("PostHog did not say which region the account is in")
        return OAuthTokens(
            accessToken = json.string("access_token") ?: throw AuthException("PostHog did not return an access token"),
            refreshToken = json.string("refresh_token"),
            expiresAtMs = clock() + (json.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong ?: 3600L) * 1000,
            host = host,
            scopes = json.string("scope")?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList(),
        )
    }

    private fun postForm(url: String, fields: Map<String, String>): HttpResponse<String> {
        val body = fields.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return send(request)
    }

    private fun send(request: HttpRequest): HttpResponse<String> = try {
        http.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: IOException) {
        throw AuthException("Could not reach PostHog: ${e.message}", cause = e)
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
}

internal fun parseObject(body: String): JsonObject = try {
    JsonParser.parseString(body).asJsonObject
} catch (e: RuntimeException) {
    throw AuthException("PostHog returned an unexpected response", cause = e)
}

internal fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
