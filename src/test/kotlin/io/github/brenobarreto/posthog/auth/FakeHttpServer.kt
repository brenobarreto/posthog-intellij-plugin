package io.github.brenobarreto.posthog.auth

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A local stand-in for PostHog's endpoints; records every request it receives. */
class FakeHttpServer : AutoCloseable {
    data class Recorded(val method: String, val path: String, val body: String, val headers: Map<String, String>) {
        val form: Map<String, String> get() = parseQuery(body)
    }

    val requests = CopyOnWriteArrayList<Recorded>()
    private val handlers = ConcurrentHashMap<String, (Recorded) -> Pair<Int, String>>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)

    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val recorded = Recorded(
                exchange.requestMethod, exchange.requestURI.path,
                exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
            )
            requests += recorded
            val (status, body) = handlers[recorded.path]?.invoke(recorded) ?: (404 to "{}")
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun on(path: String, handler: (Recorded) -> Pair<Int, String>) {
        handlers[path] = handler
    }

    fun on(path: String, status: Int, body: String) = on(path) { status to body }

    override fun close() = server.stop(0)
}
