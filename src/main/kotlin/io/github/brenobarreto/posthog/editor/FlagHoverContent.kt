package io.github.brenobarreto.posthog.editor

import io.github.brenobarreto.posthog.api.Experiment
import io.github.brenobarreto.posthog.api.ExperimentStatus
import io.github.brenobarreto.posthog.api.FeatureFlag
import io.github.brenobarreto.posthog.api.FlagVariant
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where the hover's links go; null when signed out or no project is selected. */
data class HoverLinks(val flagUrl: String?, val experimentUrl: String?, val flagsUrl: String)

/**
 * The HTML shown when hovering a flag check, ported from `buildHover` in posthog-vscode's
 * `flagDecorationProvider.ts` (MIT; see NOTICE). Everything that comes from PostHog is escaped.
 * "Copy key" is a `posthog-copy:` link handled by the popup, and the create-flag action is left out
 * because v0.1 is read-only.
 */
object FlagHoverContent {
    const val COPY_SCHEME = "posthog-copy:"

    fun html(
        key: String,
        flag: FeatureFlag?,
        experiment: Experiment?,
        links: HoverLinks?,
        now: Instant = Instant.now(),
    ): String {
        val body = StringBuilder()
        if (flag == null) {
            body.append("<p><b>⚠️ <code>${escape(key)}</code></b></p>")
            body.append("<p>This feature flag does not exist in PostHog.</p>")
        } else {
            body.append("<p><b>${if (flag.active) "🟢" else "⚪"} <code>${escape(key)}</code> · ${if (flag.active) "Active" else "Inactive"}</b></p>")
            if (experiment != null) {
                experimentSection(body, experiment, now)
            } else if (flag.variants.isNotEmpty()) {
                body.append("<hr><pre>${escape(variantChart(flag.variants))}</pre>")
            } else {
                flag.rollout?.let { body.append("<hr><pre>${escape(booleanChart(it))}</pre>") }
            }
            val conditions = flag.conditionCount
            if (conditions > 0) body.append("<p>🎯 ${FlagPresentation.plural(conditions, "release condition")}</p>")
            flag.createdBy?.let { who ->
                val date = flag.createdAt?.let(::formatDate)
                body.append("<p>👤 ${escape(who)}${if (date != null) " · ${escape(date)}" else ""}</p>")
            }
        }
        body.append("<p>")
        val open = if (flag != null) links?.flagUrl else links?.flagsUrl
        if (open != null) {
            body.append("<a href=\"${escape(open)}\">${if (flag != null) "Open in PostHog" else "Open feature flags in PostHog"}</a> &nbsp; ")
        }
        if (experiment != null && links?.experimentUrl != null) {
            body.append("<a href=\"${escape(links.experimentUrl)}\">Open experiment</a> &nbsp; ")
        }
        body.append("<a href=\"$COPY_SCHEME${URLEncoder.encode(key, Charsets.UTF_8)}\">Copy key</a></p>")
        return "<html><body>$body</body></html>"
    }

    private fun experimentSection(out: StringBuilder, experiment: Experiment, now: Instant) {
        val (icon, status) = when (experiment.status) {
            ExperimentStatus.COMPLETE -> "✅" to "Complete"
            ExperimentStatus.RUNNING -> "🧪" to "Running"
            ExperimentStatus.DRAFT -> "📝" to "Draft"
        }
        out.append("<p>$icon <b>${escape(experiment.name)}</b> · <i>$status</i></p>")

        val start = experiment.startDate?.let(::parseInstant)
        if (start != null) {
            val end = experiment.endDate?.let(::parseInstant) ?: now
            val days = maxOf(0L, Math.ceilDiv(Duration.between(start, end).toMillis(), 86_400_000L))
            out.append("<p>⏱ $days day${if (days == 1L) "" else "s"}${if (experiment.endDate == null) " so far" else ""}</p>")
        }
        when (experiment.conclusion) {
            "won" -> out.append("<p>🏆 <b>Winner declared</b></p>")
            null -> Unit
            else -> out.append("<p>❌ <b>No winner</b></p>")
        }
        experiment.conclusionComment?.lineSequence()?.firstOrNull()?.takeIf(String::isNotBlank)?.let {
            out.append("<p><i>${escape(it)}</i></p>")
        }
        if (experiment.variants.isNotEmpty()) {
            out.append("<hr><p><b>Variant allocation</b></p><pre>${escape(variantChart(experiment.variants))}</pre>")
        }
        if (experiment.metrics.isNotEmpty()) {
            out.append("<p>📊 <b>Metrics</b></p>")
            experiment.metrics.forEach { m ->
                val goal = when (m.goal) {
                    "increase" -> " ↑"
                    "decrease" -> " ↓"
                    else -> ""
                }
                out.append("<p>${metricIcon(m.type)} ${escape(m.name.ifBlank { "Unnamed" })}$goal</p>")
            }
        }
    }

    private fun metricIcon(type: String?) = when (type) {
        "funnel" -> "🔻"
        "mean" -> "📈"
        "ratio" -> "⚖️"
        "retention" -> "🔄"
        else -> "📊"
    }

    internal fun variantChart(variants: List<FlagVariant>): String {
        val keyWidth = variants.maxOf { it.key.length }
        return variants.withIndex().joinToString("\n") { (i, v) ->
            val connector = if (i == variants.lastIndex) "└" else "├"
            "  $connector─ ${v.key.padEnd(keyWidth)}  ${bar(v.rolloutPercentage, 20)}  ${(FlagPresentation.percent(v.rolloutPercentage) + "%").padStart(4)}"
        }
    }

    internal fun booleanChart(rollout: Double): String {
        val on = (FlagPresentation.percent(rollout) + "%").padStart(4)
        val off = (FlagPresentation.percent(100 - rollout) + "%").padStart(4)
        return "  ├─ true   ${bar(rollout, 20)}  $on\n  └─ false  ${bar(100 - rollout, 20)}  $off"
    }

    private fun bar(percent: Double, width: Int): String {
        val filled = Math.round(percent / 100 * width).toInt().coerceIn(0, width)
        return "█".repeat(filled) + "░".repeat(width - filled)
    }

    private fun parseInstant(text: String): Instant? =
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }

    private fun formatDate(text: String): String? =
        parseInstant(text)?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    internal fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
