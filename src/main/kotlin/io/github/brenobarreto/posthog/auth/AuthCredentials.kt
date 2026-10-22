package io.github.brenobarreto.posthog.auth

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

enum class AuthMethod { OAUTH, API_KEY }

/** What is kept in the password safe: either an OAuth session or a personal API key. */
data class AuthCredentials(
    val method: AuthMethod,
    val host: String,
    /** OAuth access token, or the personal API key. */
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long = 0,
    val scopes: List<String> = emptyList(),
    val accountLabel: String? = null,
    val scopedTeams: List<Long> = emptyList(),
) {
    fun toJson(): String = gson.toJson(this)

    fun toTokens() = OAuthTokens(accessToken, refreshToken, expiresAtMs, host, scopes, scopedTeams)

    companion object {
        private val gson = Gson()

        /** Returns null for anything unreadable, so a corrupt entry just means "signed out". */
        fun fromJson(json: String): AuthCredentials? {
            val parsed = try {
                gson.fromJson(json, AuthCredentials::class.java)
            } catch (_: JsonSyntaxException) {
                null
            } ?: return null
            // Gson fills fields reflectively and skips Kotlin's null checks, so a missing field arrives as null.
            @Suppress("USELESS_CAST")
            val complete = (parsed.method as AuthMethod?) != null &&
                !(parsed.host as String?).isNullOrEmpty() &&
                !(parsed.accessToken as String?).isNullOrEmpty()
            if (!complete) return null
            // Entries written by earlier versions lack newer list fields.
            return parsed.copy(
                scopes = (parsed.scopes as List<String>?).orEmpty(),
                scopedTeams = (parsed.scopedTeams as List<Long>?).orEmpty(),
            )
        }
    }
}
