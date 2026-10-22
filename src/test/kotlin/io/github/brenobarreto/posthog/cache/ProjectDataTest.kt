package io.github.brenobarreto.posthog.cache

import io.github.brenobarreto.posthog.api.Experiment
import io.github.brenobarreto.posthog.api.FeatureFlag
import io.github.brenobarreto.posthog.api.PostHogApi
import io.github.brenobarreto.posthog.api.PostHogApiException
import io.github.brenobarreto.posthog.api.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ProjectDataTest {
    private class FakeApi : PostHogApi {
        val flagCalls = AtomicInteger()
        val experimentCalls = AtomicInteger()
        @Volatile var flags: (Long) -> List<FeatureFlag> = { emptyList() }
        @Volatile var experimentList: (Long) -> List<Experiment> = { emptyList() }
        @Volatile var flagGate: ((Long) -> CompletableDeferred<Unit>?)? = null

        override suspend fun projects() = emptyList<Project>()

        override suspend fun featureFlags(projectId: Long): List<FeatureFlag> {
            flagCalls.incrementAndGet()
            flagGate?.invoke(projectId)?.await()
            return flags(projectId)
        }

        override suspend fun experiments(projectId: Long): List<Experiment> {
            experimentCalls.incrementAndGet()
            return experimentList(projectId)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val api = FakeApi()
    private val events = CopyOnWriteArrayList<DataKind>()

    @After
    fun tearDown() = scope.cancel()

    private fun data(intervalMs: Long = 25) =
        ProjectData(api, scope, flagIntervalMs = intervalMs, experimentIntervalMs = intervalMs, onChange = { events += it })

    private fun flag(key: String, active: Boolean = true) = FeatureFlag(key.hashCode().toLong(), key, "", active, false, null, null)

    private fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    private fun error(status: Int) = PostHogApiException(status, "HTTP $status")

    @Test
    fun `selecting a project loads flags and experiments and announces them`() {
        api.flags = { listOf(flag("a")) }
        api.experimentList = { listOf(Experiment(1, "e", null, null, "a", null)) }
        val data = data()
        data.setProject(7)

        eventually { data.flags.isLoaded && data.experiments.isLoaded }
        assertEquals("a", data.flags.flag("a")?.key)
        assertEquals(1L, data.experiments.forFlag("a")?.id)
        assertTrue(DataKind.FLAGS in events && DataKind.EXPERIMENTS in events)
    }

    @Test
    fun `an empty project still counts as loaded and announces it`() {
        val data = data()
        data.setProject(7)
        eventually { data.flags.isLoaded }
        assertTrue(data.flags.items.isEmpty())
        assertTrue(DataKind.FLAGS in events)
    }

    @Test
    fun `polling picks up changes and stays quiet when nothing changed`() {
        api.flags = { listOf(flag("a")) }
        val data = data()
        data.setProject(7)
        eventually { api.flagCalls.get() >= 4 }
        assertEquals(1, events.count { it == DataKind.FLAGS })

        api.flags = { listOf(flag("a", active = false)) }
        eventually { data.flags.flag("a")?.active == false }
        assertEquals(2, events.count { it == DataKind.FLAGS })
    }

    @Test
    fun `a failed refresh keeps the last good data and recovers`() {
        api.flags = { listOf(flag("a")) }
        val data = data()
        data.setProject(7)
        eventually { data.flags.isLoaded }

        api.flags = { throw error(500) }
        eventually { data.flagError != null }
        assertEquals("a", data.flags.flag("a")?.key)
        assertTrue(DataKind.STATUS in events)

        api.flags = { listOf(flag("a"), flag("b")) }
        eventually { data.flagError == null && data.flags.flag("b") != null }
    }

    @Test
    fun `an auth failure stops that polling loop until restarted`() {
        api.flags = { throw error(401) }
        val data = data()
        data.setProject(7)
        eventually { data.flagError?.isAuthFailure == true }
        Thread.sleep(150)
        assertEquals(1, api.flagCalls.get())
        assertFalse(data.isPolling)

        api.flags = { listOf(flag("a")) }
        data.start()
        eventually { data.flags.isLoaded }
        assertTrue(data.isPolling)
        assertNull(data.flagError)
    }

    @Test
    fun `flags keep loading when experiments are forbidden`() {
        api.flags = { listOf(flag("a")) }
        api.experimentList = { throw error(403) }
        val data = data()
        data.setProject(7)

        eventually { data.flags.isLoaded && data.experimentError != null }
        assertNull(data.flagError)
        assertFalse(data.experiments.isLoaded)
    }

    @Test
    fun `switching project drops the old data and ignores a late answer`() {
        val gate = CompletableDeferred<Unit>()
        api.flagGate = { id -> if (id == 1L) gate else null }
        api.flags = { id -> listOf(flag("project-$id")) }
        val data = data(intervalMs = 60_000)

        data.setProject(1)
        eventually { api.flagCalls.get() == 1 }
        data.setProject(2)
        eventually { data.flags.flag("project-2") != null }

        gate.complete(Unit) // project 1's response arrives late
        Thread.sleep(100)
        assertNull(data.flags.flag("project-1"))
        assertNotNull(data.flags.flag("project-2"))
    }

    @Test
    fun `clear stops polling and forgets everything`() {
        api.flags = { listOf(flag("a")) }
        val data = data()
        data.setProject(7)
        eventually { data.flags.isLoaded }

        data.clear()
        val calls = api.flagCalls.get()
        Thread.sleep(150)
        assertEquals(calls, api.flagCalls.get())
        assertFalse(data.flags.isLoaded)
        assertNull(data.flags.flag("a"))
        assertFalse(data.isPolling)
    }

    @Test
    fun `no project means no requests`() {
        val data = data()
        data.start()
        runBlocking { data.refreshAll() }
        assertEquals(0, api.flagCalls.get())
        assertEquals(0, api.experimentCalls.get())
    }

    @Test
    fun `manual refresh returns the failure`() = runBlocking {
        val data = data(intervalMs = 60_000)
        data.setProject(7)
        eventually { data.flags.isLoaded }
        api.flags = { throw error(500) }
        assertEquals(500, data.refreshFlags()?.status)
        api.flags = { listOf(flag("z")) }
        assertNull(data.refreshFlags())
        assertEquals("z", data.flags.flag("z")?.key)
    }
}
