package io.github.brenobarreto.posthog.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import kotlin.concurrent.thread

class LoopbackCallbackServerTest {
    private fun get(redirectUri: String, pathAndQuery: String): String {
        val port = redirectUri.substringAfterLast(':').substringBefore('/').toInt()
        return Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $pathAndQuery HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test
    fun `returns decoded query parameters and shows a success page`() {
        LoopbackCallbackServer().use { server ->
            var page = ""
            thread { page = get(server.redirectUri, "/callback?code=a%20b&state=xyz") }
            val params = server.awaitCallback(5_000)
            assertEquals(mapOf("code" to "a b", "state" to "xyz"), params)
            Thread.sleep(200)
            assertTrue(page.startsWith("HTTP/1.1 200 OK"))
            assertTrue("Signed in" in page)
        }
    }

    @Test
    fun `ignores other paths such as favicon and idle connections`() {
        LoopbackCallbackServer().use { server ->
            val port = server.redirectUri.substringAfterLast(':').substringBefore('/').toInt()
            val idle = Socket("127.0.0.1", port) // a speculative connection that never sends anything
            thread {
                get(server.redirectUri, "/favicon.ico")
                get(server.redirectUri, "/callback?error=access_denied&state=s")
            }
            val params = server.awaitCallback(5_000)
            idle.close()
            assertEquals(mapOf("error" to "access_denied", "state" to "s"), params)
        }
    }

    @Test
    fun `times out when nothing arrives`() {
        LoopbackCallbackServer().use { server ->
            assertThrows(AuthException::class.java) { server.awaitCallback(300) }
        }
    }

    @Test
    fun `listens on the loopback address only`() {
        LoopbackCallbackServer().use { assertTrue(it.redirectUri.startsWith("http://127.0.0.1:")) }
    }
}
