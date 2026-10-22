package io.github.brenobarreto.posthog.api

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases ported from posthog-vscode's `flagClassification.test.ts` (MIT; see NOTICE). */
class ModelsTest {
    private fun flag(filters: String? = null, rollout: Double? = null) = FeatureFlag(
        1, "flag", "", true, false, rollout, filters?.let { JsonParser.parseString(it).asJsonObject },
    )

    @Test
    fun `top-level rollout wins, and zero is not null`() {
        assertEquals(50.0, flag(rollout = 50.0).rollout)
        assertEquals(0.0, flag(rollout = 0.0).rollout)
        assertEquals(50.0, flag("""{"groups":[{"rollout_percentage":80}]}""", rollout = 50.0).rollout)
    }

    @Test
    fun `rollout falls back to the first group that has one`() {
        assertEquals(75.0, flag("""{"groups":[{"rollout_percentage":75}]}""").rollout)
        assertEquals(30.0, flag("""{"groups":[{"properties":[]},{"rollout_percentage":30},{"rollout_percentage":90}]}""").rollout)
        assertEquals(20.0, flag("""{"groups":[{"rollout_percentage":20},{"rollout_percentage":90}]}""").rollout)
    }

    @Test
    fun `rollout is null when there is none`() {
        assertNull(flag().rollout)
        assertNull(flag("""{"groups":[{"properties":[]}]}""").rollout)
        assertNull(flag("""{"multivariate":{}}""").rollout)
    }

    @Test
    fun `variants come from filters multivariate`() {
        val variants = flag("""{"multivariate":{"variants":[{"key":"control","rollout_percentage":50},{"key":"test","rollout_percentage":50}]}}""").variants
        assertEquals(listOf(FlagVariant("control", 50.0), FlagVariant("test", 50.0)), variants)
    }

    @Test
    fun `no variants for boolean flags or empty or missing variant lists`() {
        assertTrue(flag().variants.isEmpty())
        assertTrue(flag("""{"groups":[]}""").variants.isEmpty())
        assertTrue(flag("""{"multivariate":{"variants":[]}}""").variants.isEmpty())
        assertTrue(flag("""{"multivariate":{}}""").variants.isEmpty())
    }

    @Test
    fun `only groups with property filters count as conditions`() {
        assertEquals(0, flag().conditionCount)
        assertEquals(0, flag("""{"groups":[]}""").conditionCount)
        assertEquals(0, flag("""{"groups":[{"properties":[]},{"rollout_percentage":100}]}""").conditionCount)
        assertEquals(1, flag("""{"groups":[{"properties":[{"key":"email"}]},{"properties":[]}]}""").conditionCount)
        assertEquals(2, flag("""{"groups":[{"properties":[{"key":"a"}]},{"properties":[{"key":"b"},{"key":"c"}]}]}""").conditionCount)
    }

    @Test
    fun `parses a flag from the API and ignores unknown fields`() {
        val parsed = JsonParser.parseString(
            """{"id":12,"key":"new-checkout","name":"New checkout","active":true,"deleted":false,"rollout_percentage":null,
               "filters":{"groups":[{"properties":[{"key":"plan"}],"rollout_percentage":25}]},"created_by":{"email":"x@y.z"},"tags":[]}""",
        ).asJsonObject.parseFlag()!!

        assertEquals(12L, parsed.id)
        assertEquals("new-checkout", parsed.key)
        assertTrue(parsed.active)
        assertNull(parsed.rolloutPercentage)
        assertEquals(25.0, parsed.rollout)
        assertEquals(1, parsed.conditionCount)
    }

    @Test
    fun `a flag without id or key is skipped`() {
        assertNull(JsonParser.parseString("""{"key":"a"}""").asJsonObject.parseFlag())
        assertNull(JsonParser.parseString("""{"id":1}""").asJsonObject.parseFlag())
        assertNotNull(JsonParser.parseString("""{"id":1,"key":"a"}""").asJsonObject.parseFlag())
    }

    @Test
    fun `experiment status follows the dates`() {
        fun status(start: String?, end: String?) = Experiment(1, "e", start, end, "flag", null).status
        assertEquals(ExperimentStatus.DRAFT, status(null, null))
        assertEquals(ExperimentStatus.RUNNING, status("2026-01-01", null))
        assertEquals(ExperimentStatus.COMPLETE, status("2026-01-01", "2026-02-01"))
        assertEquals(ExperimentStatus.COMPLETE, status(null, "2026-02-01"))
    }

    @Test
    fun `parses an experiment and needs a flag key`() {
        val json = """{"id":3,"name":"Pricing","start_date":"2026-01-01T00:00:00Z","end_date":null,"feature_flag_key":"pricing","conclusion":null}"""
        val parsed = JsonParser.parseString(json).asJsonObject.parseExperiment()!!
        assertEquals("pricing", parsed.featureFlagKey)
        assertEquals(ExperimentStatus.RUNNING, parsed.status)
        assertNull(JsonParser.parseString("""{"id":3}""").asJsonObject.parseExperiment())
        assertFalse(parsed.conclusion != null)
    }
}
