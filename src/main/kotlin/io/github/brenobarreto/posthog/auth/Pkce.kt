package io.github.brenobarreto.posthog.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636) helpers; PostHog only accepts the S256 method. */
object Pkce {
    private val random = SecureRandom()

    fun newVerifier(): String = randomToken(32)

    fun newState(): String = randomToken(16)

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun randomToken(bytes: Int) = base64Url(ByteArray(bytes).also(random::nextBytes))

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
