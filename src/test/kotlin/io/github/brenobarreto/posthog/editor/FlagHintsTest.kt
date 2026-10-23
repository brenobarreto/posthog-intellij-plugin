package io.github.brenobarreto.posthog.editor

import io.github.brenobarreto.posthog.cache.ExperimentCache
import io.github.brenobarreto.posthog.cache.FlagCache
import io.github.brenobarreto.posthog.detect.ScanLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlagHintsTest {
    private fun compute(
        text: String,
        language: ScanLanguage = ScanLanguage.JAVASCRIPT,
        flags: List<io.github.brenobarreto.posthog.api.FeatureFlag> = emptyList(),
        experiments: List<io.github.brenobarreto.posthog.api.Experiment> = emptyList(),
        extraClientNames: Set<String> = emptySet(),
    ): List<HintSpec> {
        val flagCache = FlagCache().apply { update(flags, 0) }
        val experimentCache = ExperimentCache().apply { update(experiments, 0) }
        return FlagHints.compute(text, language, flagCache, experimentCache, extraClientNames)
    }

    @Test
    fun `puts the hint at the end of the key's line`() {
        val text = "const a = 1\nif (posthog.isFeatureEnabled('beta')) {\n  run()\n}\n"
        val spec = compute(text, flags = listOf(flag("beta", rollout = 50.0))).single()

        assertEquals(1, spec.line)
        assertEquals(text.indexOf('\n', text.indexOf("beta")), spec.lineEnd)
        assertEquals("'beta'", text.substring(spec.keyStart, spec.keyEnd))
        assertEquals("● 50%", spec.presentation.text)
        assertTrue(spec.known)
    }

    @Test
    fun `the last line without a trailing newline ends at the text end`() {
        val text = "posthog.getFeatureFlag('last')"
        assertEquals(text.length, compute(text, flags = listOf(flag("last"))).single().lineEnd)
    }

    @Test
    fun `unknown flags are marked`() {
        val spec = compute("posthog.getFeatureFlag('nope')").single()
        assertFalse(spec.known)
        assertEquals("⚠ not in PostHog", spec.presentation.text)
    }

    @Test
    fun `deleted flags count as unknown`() {
        assertFalse(compute("posthog.getFeatureFlag('old')", flags = listOf(flag("old", deleted = true))).single().known)
    }

    @Test
    fun `two checks on one line share the line end`() {
        val specs = compute(
            "const a = posthog.getFeatureFlag('one'), b = posthog.getFeatureFlag('two')",
            flags = listOf(flag("one", rollout = 10.0), flag("two", rollout = 20.0)),
        )
        assertEquals(listOf("● 10%", "● 20%"), specs.map { it.presentation.text })
        assertEquals(1, specs.map { it.lineEnd }.distinct().size)
        assertEquals(listOf(0, 0), specs.map { it.line })
    }

    @Test
    fun `line numbers count every earlier line`() {
        val text = "\n\n\nposthog.getFeatureFlag('a')\n\nposthog.getFeatureFlag('b')\n"
        assertEquals(listOf(3, 5), compute(text).map { it.line })
    }

    @Test
    fun `a linked experiment changes the label`() {
        val spec = compute(
            "posthog.getFeatureFlag('exp')", flags = listOf(flag("exp")),
            experiments = listOf(experiment("exp", start = "2026-01-01T00:00:00Z")),
        ).single()
        assertEquals("⚗ experiment running", spec.presentation.text)
    }

    @Test
    fun `the cache settles an ambiguous call`() {
        val text = "client.getFeatureFlag(\"user123\", \"feature_key\")"
        val spec = compute(text, ScanLanguage.KOTLIN, flags = listOf(flag("feature_key", rollout = 100.0))).single()
        assertEquals("feature_key", spec.key)
        assertTrue(spec.known)
    }

    @Test
    fun `extra client names are passed through`() {
        assertEquals(1, compute("analytics.getFeatureFlag('x')", extraClientNames = setOf("analytics")).size)
        assertEquals(0, compute("analytics.getFeatureFlag('x')").size)
    }

    @Test
    fun `a file without checks has no hints`() = assertTrue(compute("const x = 1;\n").isEmpty())
}
