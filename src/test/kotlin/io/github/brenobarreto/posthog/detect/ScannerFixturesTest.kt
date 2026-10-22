package io.github.brenobarreto.posthog.detect

import io.github.brenobarreto.posthog.detect.ScanLanguage.JAVA
import io.github.brenobarreto.posthog.detect.ScanLanguage.JAVASCRIPT
import io.github.brenobarreto.posthog.detect.ScanLanguage.KOTLIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Real sources from posthog-vscode's playgrounds and posthog-android's samples (see fixtures/README.md).
 * The expected keys were read off the files by hand.
 */
class ScannerFixturesTest {
    private fun fixture(path: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$path")) { "missing fixture $path" }.readBytes().toString(Charsets.UTF_8)

    private fun scanFixture(path: String, language: ScanLanguage): List<FlagCall> {
        val text = fixture(path)
        val calls = TextFlagCallScanner.scan(text, language)
        calls.forEach { assertEquals(path, text.substring(it.keyStart + 1, it.keyEnd - 1), it.key) }
        assertEquals("calls come in source order", calls.sortedBy { it.keyStart }, calls)
        return calls
    }

    private fun assertKeys(path: String, language: ScanLanguage, vararg expected: String) =
        assertEquals(path, expected.toList(), scanFixture(path, language).map { it.key })

    @Test
    fun `vscode playground javascript`() =
        assertKeys("vscode-playgrounds/javascript-index.js", JAVASCRIPT, "flag-that-doesnt-exist", "aa-test-bayesian-new", "active-hours-heatmap")

    @Test
    fun `vscode playground typescript has only empty keys in index and one flag in the app`() {
        assertKeys("vscode-playgrounds/typescript-index.ts", JAVASCRIPT)
        assertKeys("vscode-playgrounds/typescript-App.tsx", JAVASCRIPT, "file-engagement-v2")
    }

    @Test
    fun `vscode playground react native typescript`() = assertKeys(
        "vscode-playgrounds/react-native-ts-App.tsx", JAVASCRIPT,
        "promo-banner", "signup-experiment", "dashboard-layout", "dark-mode", "checkout-flow-v2", "feature-limits",
    )

    @Test
    fun `vscode playground react native javascript`() = assertKeys(
        "vscode-playgrounds/react-native-js-App.jsx", JAVASCRIPT,
        "new-onboarding-flow", "checkout-experiment", "dashboard-config", "beta-features", "pricing-page-test", "remote-settings",
    )

    @Test
    fun `vscode playground node typescript`() =
        assertKeys("vscode-playgrounds/node-ts-server.ts", JAVASCRIPT, "new-dashboard", "onboarding-flow", "feature-limits", "pricing-test")

    @Test
    fun `vscode playground node javascript`() =
        assertKeys("vscode-playgrounds/node-js-server.js", JAVASCRIPT, "new-api-endpoint", "onboarding-wizard-prominence", "rate-limits")

    @Test
    fun `android sample`() = assertKeys("posthog-android/NormalActivity.kt", KOTLIN, "enable_network_request")

    @Test
    fun `server java sample with evaluateFlags`() =
        assertKeys("posthog-android/PostHogJavaExample.java", JAVA, "beta-feature", "multi-variate-flag", "multi-variate-flag", "file-previews")

    @Test
    fun `fixtures resolve to the line that holds the key`() {
        val text = fixture("vscode-playgrounds/react-native-ts-App.tsx")
        val call = TextFlagCallScanner.scan(text, JAVASCRIPT).first { it.key == "dark-mode" }
        val line = text.substring(0, call.keyStart).count { it == '\n' }
        assertTrue(text.lines()[line].contains("posthog.isFeatureEnabled('dark-mode')"))
    }

    @Test
    fun `file names map to languages`() {
        val expected = mapOf(
            "App.js" to JAVASCRIPT, "a.jsx" to JAVASCRIPT, "a.mjs" to JAVASCRIPT, "a.cjs" to JAVASCRIPT, "a.ts" to JAVASCRIPT,
            "A.TSX" to JAVASCRIPT, "a.mts" to JAVASCRIPT, "a.cts" to JAVASCRIPT,
            "A.java" to JAVA, "A.kt" to KOTLIN, "build.gradle.kts" to KOTLIN,
            "a.py" to null, "README" to null, "a.json" to null, "a.kt.txt" to null,
        )
        expected.forEach { (name, language) -> assertEquals(name, language, ScanLanguage.forFileName(name)) }
    }

    @Test
    fun `never throws on arbitrary text`() {
        val random = Random(42)
        val alphabet = "'\"`/*(){}[]<>\$.,?!=:;\\\n \tabposthogetFeatureFlag01"
        repeat(3_000) {
            val text = String(CharArray(random.nextInt(0, 200)) { alphabet[random.nextInt(alphabet.length)] })
            ScanLanguage.entries.forEach { TextFlagCallScanner.scan(text, it) }
        }
    }

    @Test
    fun `stays fast on a large file full of unfinished calls`() {
        val text = "posthog.getFeatureFlag(\n".repeat(20_000)
        val millis = ScanLanguage.entries.sumOf { language -> measureTimeMillis { TextFlagCallScanner.scan(text, language) } }
        assertTrue("took ${millis}ms", millis < 15_000)
    }

    @Test
    fun `scans a megabyte of ordinary code quickly`() {
        val block = "const a = posthog.getFeatureFlag('flag-a');\nfunction f(x) { return x + 1; } // comment\n"
        val text = block.repeat(15_000)
        var calls = emptyList<FlagCall>()
        val millis = measureTimeMillis { calls = TextFlagCallScanner.scan(text, JAVASCRIPT) }
        assertEquals(15_000, calls.size)
        assertTrue("took ${millis}ms", millis < 5_000)
    }
}
