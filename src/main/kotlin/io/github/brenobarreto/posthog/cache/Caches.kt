package io.github.brenobarreto.posthog.cache

import io.github.brenobarreto.posthog.api.Experiment
import io.github.brenobarreto.posthog.api.FeatureFlag

/**
 * An immutable snapshot of one kind of PostHog data. Safe to read from any thread (including the EDT)
 * while a background refresh replaces it.
 */
abstract class SnapshotCache<T> {
    @Volatile private var snapshot: List<T> = emptyList()

    @Volatile var lastRefreshedMs: Long? = null
        private set

    /** False until the first successful fetch, so "not found" isn't reported before the data has arrived. */
    val isLoaded: Boolean get() = lastRefreshedMs != null

    val items: List<T> get() = snapshot

    /** Replaces the contents; returns true when listeners should be told (new data, or the first load). */
    fun update(newItems: List<T>, nowMs: Long): Boolean {
        val changed = !isLoaded || newItems != snapshot
        reindex(newItems)
        snapshot = newItems
        lastRefreshedMs = nowMs
        return changed
    }

    /** Forgets everything, e.g. on sign-out or when the project changes; returns true if there was data. */
    fun clear(): Boolean {
        val hadData = isLoaded
        reindex(emptyList())
        snapshot = emptyList()
        lastRefreshedMs = null
        return hadData
    }

    protected abstract fun reindex(items: List<T>)
}

class FlagCache : SnapshotCache<FeatureFlag>() {
    @Volatile private var byKey: Map<String, FeatureFlag> = emptyMap()

    /** Deleted flags count as absent. */
    fun flag(key: String): FeatureFlag? = byKey[key]

    val keys: Set<String> get() = byKey.keys

    override fun reindex(items: List<FeatureFlag>) {
        byKey = items.filterNot { it.deleted }.associateBy { it.key }
    }
}

class ExperimentCache : SnapshotCache<Experiment>() {
    @Volatile private var byFlagKey: Map<String, Experiment> = emptyMap()

    /** The first experiment listed for the flag, as in the VS Code extension. */
    fun forFlag(flagKey: String): Experiment? = byFlagKey[flagKey]

    override fun reindex(items: List<Experiment>) {
        byFlagKey = buildMap { items.forEach { putIfAbsent(it.featureFlagKey, it) } }
    }
}
