package io.github.brenobarreto.posthog.auth

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * One-shot HTTP listener on `127.0.0.1` for the OAuth redirect (RFC 8252, section 7.3). Uses a plain
 * [ServerSocket] so it doesn't depend on `jdk.httpserver` being present in the IDE's runtime.
 */
internal class LoopbackCallbackServer : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

    val redirectUri = "http://127.0.0.1:${server.localPort}${OAuthConfig.CALLBACK_PATH}"

    /** Blocks until the browser hits the callback path and returns its query parameters. */
    fun awaitCallback(timeoutMs: Long, checkCancelled: () -> Unit = {}): Map<String, String> {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        server.soTimeout = POLL_MS
        while (System.nanoTime() < deadline) {
            checkCancelled()
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            socket.use { handle(it)?.let { params -> return params } }
        }
        throw AuthException("Timed out waiting for the browser sign-in to finish")
    }

    override fun close() = server.close()

    private fun handle(socket: Socket): Map<String, String>? {
        val requestLine = try {
            socket.soTimeout = READ_TIMEOUT_MS
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1)).readLine()
        } catch (_: IOException) {
            null
        } ?: return null // Browsers open speculative connections that never send anything.

        val target = requestLine.split(' ').getOrNull(1) ?: return null
        val path = target.substringBefore('?')
        if (path != OAuthConfig.CALLBACK_PATH) {
            respond(socket, "404 Not Found", "Not found")
            return null
        }
        val params = parseQuery(target.substringAfter('?', ""))
        respond(
            socket, "200 OK",
            if ("code" in params) "Signed in. You can close this tab and return to your IDE."
            else "Sign-in was not completed. You can close this tab and try again from your IDE.",
        )
        return params
    }

    private fun respond(socket: Socket, status: String, message: String) {
        val body = "<!doctype html><meta charset=\"utf-8\"><title>PostHog Toolkit (Community)</title><p>$message</p>"
            .toByteArray(Charsets.UTF_8)
        try {
            socket.getOutputStream().apply {
                write(
                    ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                )
                write(body)
                flush()
            }
        } catch (_: IOException) {
            // The browser went away; the parameters are still usable.
        }
    }

    private companion object {
        const val POLL_MS = 250
        const val READ_TIMEOUT_MS = 1_000
    }
}

internal fun parseQuery(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
        val key = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8)
        val value = URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
        key to value
    }
}
