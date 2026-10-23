package io.github.brenobarreto.posthog.editor

import io.github.brenobarreto.posthog.api.Experiment
import io.github.brenobarreto.posthog.api.ExperimentMetric
import io.github.brenobarreto.posthog.api.FlagVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FlagHoverContentTest {
    private val now = Instant.parse("2026-03-10T12:00:00Z")
    private val links = HoverLinks(
        flagUrl = "https://eu.posthog.com/project/7/feature_flags/42",
        experimentUrl = "https://eu.posthog.com/project/7/experiments/1",
        flagsUrl = "https://eu.posthog.com/project/7/feature_flags",
    )

    private fun html(
        key: String = "beta",
        flag: io.github.brenobarreto.posthog.api.FeatureFlag? = flag("beta", rollout = 50.0),
        experiment: Experiment? = null,
        links: HoverLinks? = this.links,
    ) = FlagHoverContent.html(key, flag, experiment, links, now)

    @Test
    fun `an active flag shows its status and rollout chart`() {
        val html = html()
        assertTrue(html, "🟢 <code>beta</code> · Active" in html)
        assertTrue(html, "├─ true   ██████████░░░░░░░░░░   50%" in html)
        assertTrue(html, "└─ false  ██████████░░░░░░░░░░   50%" in html)
    }

    @Test
    fun `an inactive flag says so`() = assertTrue("⚪ <code>beta</code> · Inactive" in html(flag = flag("beta", active = false)))

    @Test
    fun `variants are charted in order`() {
        val html = html(flag = flag("beta", filters = multivariate("control" to 50, "test" to 30, "other" to 20)))
        assertTrue(html, "├─ control  ██████████░░░░░░░░░░   50%" in html)
        assertTrue(html, "├─ test     ██████░░░░░░░░░░░░░░   30%" in html)
        assertTrue(html, "└─ other    ████░░░░░░░░░░░░░░░░   20%" in html)
    }

    @Test
    fun `release conditions are counted`() {
        val one = """{"groups":[{"properties":[{"key":"a"}]}]}"""
        val two = """{"groups":[{"properties":[{"key":"a"}]},{"properties":[{"key":"b"}]}]}"""
        assertTrue("🎯 1 release condition</p>" in html(flag = flag("beta", filters = one)))
        assertTrue("🎯 2 release conditions</p>" in html(flag = flag("beta", filters = two)))
    }

    @Test
    fun `the creator and date are shown`() {
        val html = html(flag = flag("beta", createdBy = "Ada", createdAt = "2026-01-15T10:00:00Z"))
        assertTrue(html, "👤 Ada · " in html)
    }

    @Test
    fun `an unknown flag explains itself and offers the flag list`() {
        val html = html(flag = null)
        assertTrue(html, "⚠️ <code>beta</code>" in html)
        assertTrue(html, "does not exist in PostHog" in html)
        assertTrue(html, "href=\"https://eu.posthog.com/project/7/feature_flags\">Open feature flags in PostHog" in html)
        assertFalse(html, "Active" in html)
    }

    @Test
    fun `links point at the flag and copy the key`() {
        val html = html()
        assertTrue(html, "<a href=\"https://eu.posthog.com/project/7/feature_flags/42\">Open in PostHog</a>" in html)
        assertTrue(html, "<a href=\"posthog-copy:beta\">Copy key</a>" in html)
    }

    @Test
    fun `the copy link encodes odd keys and has no open link when signed out`() {
        val html = html(key = "a b&c", flag = flag("a b&c"), links = null)
        assertTrue(html, "posthog-copy:a+b%26c" in html)
        assertFalse(html, "Open in PostHog" in html)
    }

    @Test
    fun `a running experiment shows its name, duration and allocation`() {
        val running = Experiment(
            1, "Pricing test", "2026-03-01T00:00:00Z", null, "beta", null,
            variants = listOf(FlagVariant("control", 50.0), FlagVariant("test", 50.0)),
            metrics = listOf(ExperimentMetric("Conversion", "funnel", "increase"), ExperimentMetric("", "mean", "decrease")),
        )
        val html = html(experiment = running)
        assertTrue(html, "🧪 <b>Pricing test</b> · <i>Running</i>" in html)
        assertTrue(html, "⏱ 10 days so far" in html)
        assertTrue(html, "Variant allocation" in html)
        assertTrue(html, "🔻 Conversion ↑" in html)
        assertTrue(html, "📈 Unnamed ↓" in html)
        assertTrue(html, "<a href=\"https://eu.posthog.com/project/7/experiments/1\">Open experiment</a>" in html)
    }

    @Test
    fun `a finished experiment shows its length and conclusion`() {
        val done = Experiment(
            1, "Done", "2026-01-01T00:00:00Z", "2026-01-11T00:00:00Z", "beta", "won", conclusionComment = "Ship it\nsecond line",
        )
        val html = html(experiment = done)
        assertTrue(html, "✅ <b>Done</b> · <i>Complete</i>" in html)
        assertTrue(html, "⏱ 10 days</p>" in html)
        assertTrue(html, "🏆 <b>Winner declared</b>" in html)
        assertTrue(html, "<i>Ship it</i>" in html)
        assertFalse(html, "second line" in html)
    }

    @Test
    fun `a draft experiment has no duration and a lost one has no winner`() {
        assertFalse("⏱" in html(experiment = experiment("beta")))
        assertTrue("📝" in html(experiment = experiment("beta")))
        assertTrue("❌ <b>No winner</b>" in html(experiment = Experiment(1, "x", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "beta", "lost")))
    }

    @Test
    fun `an experiment replaces the plain rollout chart`() {
        assertFalse("true   " in html(experiment = experiment("beta", start = "2026-03-01T00:00:00Z")))
    }

    @Test
    fun `everything that comes from PostHog is escaped`() {
        val evil = "<script>alert(1)</script>\"'&"
        val html = html(
            key = evil, flag = flag(evil, createdBy = evil),
            experiment = Experiment(1, evil, "2026-03-01T00:00:00Z", null, evil, null, conclusionComment = evil, metrics = listOf(ExperimentMetric(evil, null, null))),
            links = HoverLinks("https://x.test/\"onmouseover=\"y", null, "https://x.test"),
        )
        assertFalse(html, "<script>" in html)
        assertFalse(html, "\"onmouseover=\"" in html)
        assertTrue(html, "&lt;script&gt;alert(1)&lt;/script&gt;&quot;&#39;&amp;" in html)
    }

    @Test
    fun `escape handles each special character`() =
        assertEquals("&amp;&lt;&gt;&quot;&#39;plain", FlagHoverContent.escape("&<>\"'plain"))
}
