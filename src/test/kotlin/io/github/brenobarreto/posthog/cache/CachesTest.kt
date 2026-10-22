package io.github.brenobarreto.posthog.cache

import io.github.brenobarreto.posthog.api.Experiment
import io.github.brenobarreto.posthog.api.FeatureFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachesTest {
    private fun flag(key: String, deleted: Boolean = false, active: Boolean = true) =
        FeatureFlag(key.hashCode().toLong(), key, "", active, deleted, null, null)

    private fun experiment(id: Long, flagKey: String) = Experiment(id, "e$id", null, null, flagKey, null)

    @Test
    fun `nothing is loaded until the first update, even an empty one`() {
        val cache = FlagCache()
        assertFalse(cache.isLoaded)
        assertTrue(cache.update(emptyList(), 1))
        assertTrue(cache.isLoaded)
        assertEquals(1L, cache.lastRefreshedMs)
    }

    @Test
    fun `update reports a change only for new data`() {
        val cache = FlagCache()
        assertTrue(cache.update(listOf(flag("a")), 1))
        assertFalse(cache.update(listOf(flag("a")), 2))
        assertEquals(2L, cache.lastRefreshedMs)
        assertTrue(cache.update(listOf(flag("a", active = false)), 3))
    }

    @Test
    fun `flags are found by key and deleted ones count as absent`() {
        val cache = FlagCache()
        cache.update(listOf(flag("a"), flag("gone", deleted = true)), 1)
        assertEquals("a", cache.flag("a")?.key)
        assertNull(cache.flag("gone"))
        assertNull(cache.flag("missing"))
        assertEquals(setOf("a"), cache.keys)
        assertEquals(2, cache.items.size)
    }

    @Test
    fun `clear forgets the data and reports whether there was any`() {
        val cache = FlagCache()
        assertFalse(cache.clear())
        cache.update(listOf(flag("a")), 1)
        assertTrue(cache.clear())
        assertFalse(cache.isLoaded)
        assertNull(cache.flag("a"))
        assertTrue(cache.items.isEmpty())
    }

    @Test
    fun `the first experiment listed for a flag wins`() {
        val cache = ExperimentCache()
        cache.update(listOf(experiment(1, "f"), experiment(2, "f"), experiment(3, "g")), 1)
        assertEquals(1L, cache.forFlag("f")?.id)
        assertEquals(3L, cache.forFlag("g")?.id)
        assertNull(cache.forFlag("h"))
    }
}
