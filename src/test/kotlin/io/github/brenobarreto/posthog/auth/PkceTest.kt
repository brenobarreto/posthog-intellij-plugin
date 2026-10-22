package io.github.brenobarreto.posthog.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun `challenge matches the RFC 7636 example`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `verifier and state are url safe, long enough and random`() {
        val verifier = Pkce.newVerifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(Regex("[A-Za-z0-9_-]+").matches(verifier))
        assertTrue(Regex("[A-Za-z0-9_-]+").matches(Pkce.newState()))
        assertNotEquals(verifier, Pkce.newVerifier())
    }
}
