package io.github.brenobarreto.posthog.auth

/** A sign-in or token problem whose message is safe to show to the user. */
class AuthException(
    message: String,
    /** True when the stored credentials can no longer be used and the user has to sign in again. */
    val reauthRequired: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
