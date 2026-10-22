package io.github.brenobarreto.posthog.cache

import io.github.brenobarreto.posthog.api.PostHogApi
import io.github.brenobarreto.posthog.api.PostHogApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DataKind { FLAGS, EXPERIMENTS, STATUS }

/**
 * The flags and experiments of the selected PostHog project, refreshed periodically. Platform-free so it
 * can be unit tested; [ProjectDataService] wires it to the IDE.
 *
 * Refresh intervals are the VS Code extension's: flags every minute, experiments every five.
 * A failed refresh keeps the last good data. A 401/403 stops that resource's polling until [start]
 * is called again (after signing in).
 */
class ProjectData(
    private val api: PostHogApi,
    private val scope: CoroutineScope,
    private val flagIntervalMs: Long = 60_000,
    private val experimentIntervalMs: Long = 300_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChange: (DataKind) -> Unit = {},
) {
    val flags = FlagCache()
    val experiments = ExperimentCache()

    @Volatile var projectId: Long? = null
        private set

    @Volatile var flagError: PostHogApiException? = null
        private set

    @Volatile var experimentError: PostHogApiException? = null
        private set

    /** True while both polling loops are running; false after sign-out or a 401/403. */
    val isPolling: Boolean get() = jobs.isNotEmpty() && jobs.all { it.isActive }

    private val flagLock = Mutex()
    private val experimentLock = Mutex()
    private var jobs: List<Job> = emptyList()

    /** Switches project: drops the old data and starts polling the new one (null just stops). */
    @Synchronized
    fun setProject(id: Long?) {
        if (id == projectId) return
        stop()
        projectId = id
        clearData()
        start()
    }

    /** (Re)starts polling for the current project; the first fetch happens immediately. */
    @Synchronized
    fun start() {
        stop()
        if (projectId == null) return
        jobs = listOf(
            scope.launch { poll(flagIntervalMs, ::refreshFlags) },
            scope.launch { poll(experimentIntervalMs, ::refreshExperiments) },
        )
    }

    /** Stops polling; the data stays readable. */
    @Synchronized
    fun stop() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
    }

    /** Stops polling and forgets everything, for sign-out. */
    @Synchronized
    fun clear() {
        stop()
        clearData()
    }

    suspend fun refreshAll() {
        refreshFlags()
        refreshExperiments()
    }

    /** Returns the failure, or null on success. Data from a project that was deselected meanwhile is discarded. */
    suspend fun refreshFlags(): PostHogApiException? = flagLock.withLock {
        val id = projectId ?: return null
        try {
            val fetched = api.featureFlags(id)
            if (projectId == id) {
                if (flags.update(fetched, clock())) onChange(DataKind.FLAGS)
                setFlagError(null)
            }
            null
        } catch (e: PostHogApiException) {
            if (projectId == id) setFlagError(e)
            e
        }
    }

    suspend fun refreshExperiments(): PostHogApiException? = experimentLock.withLock {
        val id = projectId ?: return null
        try {
            val fetched = api.experiments(id)
            if (projectId == id) {
                if (experiments.update(fetched, clock())) onChange(DataKind.EXPERIMENTS)
                setExperimentError(null)
            }
            null
        } catch (e: PostHogApiException) {
            if (projectId == id) setExperimentError(e)
            e
        }
    }

    private suspend fun poll(intervalMs: Long, refresh: suspend () -> PostHogApiException?) {
        while (true) {
            if (refresh()?.isAuthFailure == true) return
            delay(intervalMs)
        }
    }

    private fun clearData() {
        val hadFlags = flags.clear()
        val hadExperiments = experiments.clear()
        val hadError = flagError != null || experimentError != null
        flagError = null
        experimentError = null
        if (hadFlags) onChange(DataKind.FLAGS)
        if (hadExperiments) onChange(DataKind.EXPERIMENTS)
        if (hadError) onChange(DataKind.STATUS)
    }

    private fun setFlagError(error: PostHogApiException?) {
        val changed = flagError?.message != error?.message
        flagError = error
        if (changed) onChange(DataKind.STATUS)
    }

    private fun setExperimentError(error: PostHogApiException?) {
        val changed = experimentError?.message != error?.message
        experimentError = error
        if (changed) onChange(DataKind.STATUS)
    }
}
