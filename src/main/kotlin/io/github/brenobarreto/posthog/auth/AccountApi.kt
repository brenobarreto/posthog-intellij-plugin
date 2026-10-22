package io.github.brenobarreto.posthog.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class UserInfo(val email: String?, val firstName: String?) {
    val label: String get() = email ?: firstName ?: "PostHog user"
}

/** Works for OAuth access tokens and personal API keys alike: both go in a `Bearer` header. */
class AccountApi(private val http: HttpClient = defaultHttpClient()) {

    suspend fun fetchUser(host: String, token: String): UserInfo = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create("$host/api/users/@me/")).timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $token").header("Accept", "application/json").GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw AuthException("Could not reach $host: ${e.message}", cause = e)
        }
        when (response.statusCode()) {
            in 200..299 -> Unit
            401, 403 -> throw AuthException("PostHog rejected the credentials (HTTP ${response.statusCode()})")
            else -> throw AuthException("PostHog answered with HTTP ${response.statusCode()}")
        }
        val json = parseObject(response.body())
        UserInfo(email = json.string("email"), firstName = json.string("first_name"))
    }
}
