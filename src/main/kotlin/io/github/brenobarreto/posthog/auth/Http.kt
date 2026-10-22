package io.github.brenobarreto.posthog.auth

import java.net.http.HttpClient
import java.time.Duration

internal val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

/** Redirects are never followed, so tokens can't be forwarded to an unexpected host. */
internal fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()
