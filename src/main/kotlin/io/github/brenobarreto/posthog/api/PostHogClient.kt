package io.github.brenobarreto.posthog.api

import com.google.gson.JsonObject
import io.github.brenobarreto.posthog.auth.AuthException
import io.github.brenobarreto.posthog.auth.AuthManager
import io.github.brenobarreto.posthog.auth.REQUEST_TIMEOUT
import io.github.brenobarreto.posthog.auth.defaultHttpClient
import io.github.brenobarreto.posthog.auth.parseObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** A failed PostHog API call; [status] is 0 when PostHog could not be reached. */
class PostHogApiException(val status: Int, message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The account is signed out or the credentials were rejected, so retrying without signing in again is pointless. */
    val isAuthFailure: Boolean get() = status == 401 || status == 403
}

/** What the caches need from PostHog. An interface so they can be tested without HTTP. */
interface PostHogApi {
    suspend fun projects(): List<Project>

    suspend fun featureFlags(projectId: Long): List<FeatureFlag>

    suspend fun experiments(projectId: Long): List<Experiment>
}

/** Read-only client for the signed-in account's PostHog instance. */
class PostHogClient(
    private val auth: AuthManager,
    private val http: HttpClient = defaultHttpClient(),
) : PostHogApi {

    /** Projects the account can pick from, limited to the token's `scoped_teams` when it has any. */
    override suspend fun projects(): List<Project> {
        val scoped = auth.current()?.scopedTeams.orEmpty()
        if (scoped.isNotEmpty()) return scoped.map { project(it) }
        return paginate("/api/projects/?limit=100", maxPages = MAX_PROJECT_PAGES).mapNotNull { it.parseProject() }
    }

    suspend fun project(id: Long): Project =
        get("/api/projects/$id/").parseProject() ?: throw PostHogApiException(0, "PostHog returned an unexpected project")

    override suspend fun featureFlags(projectId: Long): List<FeatureFlag> =
        paginate("/api/projects/$projectId/feature_flags/?limit=100").mapNotNull { it.parseFlag() }

    override suspend fun experiments(projectId: Long): List<Experiment> =
        paginate("/api/projects/$projectId/experiments/?limit=100").mapNotNull { it.parseExperiment() }

    /** Collects every page. Only the path and query of `next` are followed, so the token never leaves the account's host. */
    private suspend fun paginate(firstPath: String, maxPages: Int = MAX_PAGES): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var path: String? = firstPath
        var pages = 0
        while (path != null) {
            if (++pages > maxPages) throw PostHogApiException(0, "PostHog returned more than $maxPages pages; giving up")
            val page = get(path)
            page.array("results")?.forEach { e -> e.asObjectOrNull()?.let(items::add) }
            path = page.string("next")?.let(::relativeTo)
        }
        return items
    }

    private fun relativeTo(next: String): String {
        val uri = try {
            URI(next)
        } catch (e: java.net.URISyntaxException) {
            throw PostHogApiException(0, "PostHog returned an invalid pagination link", e)
        }
        return uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    private suspend fun get(path: String): JsonObject {
        var response = send(path, forceRefresh = false)
        if (response.statusCode() == 401) response = send(path, forceRefresh = true) // renew once, e.g. after clock skew
        val status = response.statusCode()
        if (status !in 200..299) throw PostHogApiException(status, describe(status))
        return try {
            parseObject(response.body())
        } catch (e: AuthException) {
            throw PostHogApiException(0, "PostHog returned an unexpected response", e)
        }
    }

    private suspend fun send(path: String, forceRefresh: Boolean): HttpResponse<String> {
        val credentials = auth.current() ?: throw PostHogApiException(401, "Not signed in to PostHog")
        val token = try {
            auth.accessToken(forceRefresh)
        } catch (e: AuthException) {
            throw PostHogApiException(401, e.message.orEmpty(), e)
        } ?: throw PostHogApiException(401, "Not signed in to PostHog")
        val request = HttpRequest.newBuilder(URI.create(credentials.host + path)).timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $token").header("Accept", "application/json").GET().build()
        return withContext(Dispatchers.IO) {
            try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                throw PostHogApiException(0, "Could not reach ${credentials.host}: ${e.message}", e)
            }
        }
    }

    private fun describe(status: Int) = when (status) {
        401 -> "PostHog rejected the credentials (HTTP 401)"
        403 -> "The account has no access to this resource (HTTP 403)"
        404 -> "Not found (HTTP 404); check the selected project"
        429 -> "PostHog is rate limiting requests (HTTP 429)"
        else -> "PostHog answered with HTTP $status"
    }

    private companion object {
        const val MAX_PAGES = 100
        const val MAX_PROJECT_PAGES = 10
    }
}
