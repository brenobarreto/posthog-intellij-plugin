package io.github.brenobarreto.posthog.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/** Cases ported from posthog-vscode's `flagDecorationProvider.test.ts` (MIT; see NOTICE). */
class FlagPresentationTest {
    private fun hint(flag: io.github.brenobarreto.posthog.api.FeatureFlag?, experiment: io.github.brenobarreto.posthog.api.Experiment? = null) =
        FlagPresentation.hint(flag, experiment)

    @Test
    fun `fully rolled out flag is a green dot with the percentage`() =
        assertEquals(Presentation("● 100%", HintColor.ENABLED), hint(flag("on", rollout = 100.0)))

    @Test
    fun `partial rollout shows its percentage`() =
        assertEquals(Presentation("● 33%", HintColor.ENABLED), hint(flag("partial", rollout = 33.0)))

    @Test
    fun `fractional percentages keep their decimals`() =
        assertEquals("● 33.33%", hint(flag("third", rollout = 33.33)).text)

    @Test
    fun `rollout is read from the first release condition`() =
        assertEquals("● 25%", hint(flag("grouped", filters = """{"groups":[{"properties":[],"rollout_percentage":25}]}""")).text)

    @Test
    fun `a flag with no rollout information is just enabled`() =
        assertEquals("● enabled", hint(flag("plain")).text)

    @Test
    fun `inactive flag is grey`() = assertEquals(Presentation("○ inactive", HintColor.GHOST), hint(flag("off", active = false)))

    @Test
    fun `unknown flag is a yellow warning`() =
        assertEquals(Presentation("⚠ not in PostHog", HintColor.WARNING), hint(null))

    @Test
    fun `multivariate flag shows its variant count`() =
        assertEquals("● 3 variants", hint(flag("mv", filters = multivariate("a" to 34, "b" to 33, "c" to 33))).text)

    @Test
    fun `a single variant is not pluralised`() =
        assertEquals("● 1 variant", hint(flag("mv", filters = multivariate("only" to 100))).text)

    @Test
    fun `variants win over rollout`() =
        assertEquals("● 2 variants", hint(flag("mv", rollout = 50.0, filters = multivariate("a" to 50, "b" to 50))).text)

    @Test
    fun `conditions are counted after the status`() {
        val one = """{"groups":[{"properties":[{"key":"plan"}],"rollout_percentage":50}]}"""
        val two = """{"groups":[{"properties":[{"key":"a"}],"rollout_percentage":50},{"properties":[{"key":"b"}]}]}"""
        assertEquals("● 50% · 1 condition", hint(flag("c1", filters = one)).text)
        assertEquals("● 50% · 2 conditions", hint(flag("c2", filters = two)).text)
    }

    @Test
    fun `conditions follow the variant count too`() =
        assertEquals(
            "● 2 variants · 1 condition",
            hint(flag("mv", filters = """{"multivariate":{"variants":[{"key":"a","rollout_percentage":50},{"key":"b","rollout_percentage":50}]},"groups":[{"properties":[{"key":"x"}]}]}""")).text,
        )

    @Test
    fun `a linked experiment shows its status in blue`() {
        assertEquals(Presentation("⚗ experiment draft", HintColor.EXPERIMENT), hint(flag("f"), experiment("f")))
        assertEquals(Presentation("⚗ experiment running", HintColor.EXPERIMENT), hint(flag("f"), experiment("f", start = "2026-01-01T00:00:00Z")))
        assertEquals(
            Presentation("⚗ experiment complete", HintColor.EXPERIMENT),
            hint(flag("f"), experiment("f", start = "2026-01-01T00:00:00Z", end = "2026-02-01T00:00:00Z")),
        )
    }

    @Test
    fun `an inactive flag stays inactive even with an experiment`() =
        assertEquals("○ inactive", hint(flag("f", active = false), experiment("f", start = "2026-01-01T00:00:00Z")).text)

    @Test
    fun `an experiment on a missing flag is still a missing flag`() =
        assertEquals("⚠ not in PostHog", hint(null, experiment("f")).text)
}
