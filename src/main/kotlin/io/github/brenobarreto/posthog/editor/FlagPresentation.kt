package io.github.brenobarreto.posthog.editor

import io.github.brenobarreto.posthog.api.Experiment
import io.github.brenobarreto.posthog.api.FeatureFlag
import java.math.BigDecimal

enum class HintColor { ENABLED, WARNING, EXPERIMENT, GHOST }

/** What goes after a flag check. */
data class Presentation(val text: String, val color: HintColor)

/**
 * The end-of-line label for a flag, ported from `buildLabel` in posthog-vscode's
 * `flagDecorationProvider.ts` (MIT; see NOTICE). Experiment results (`⚗ test leading 72%`) are out of
 * scope for v0.1, so a linked experiment only shows its status.
 */
object FlagPresentation {
    fun hint(flag: FeatureFlag?, experiment: Experiment?): Presentation {
        if (flag == null) return Presentation("⚠ not in PostHog", HintColor.WARNING)
        if (!flag.active) return Presentation("○ inactive", HintColor.GHOST)
        if (experiment != null) {
            return Presentation("⚗ experiment ${experiment.status.name.lowercase()}", HintColor.EXPERIMENT)
        }

        val parts = mutableListOf("●")
        val rollout = flag.rollout
        parts += when {
            flag.variants.isNotEmpty() -> plural(flag.variants.size, "variant")
            rollout != null -> "${percent(rollout)}%"
            else -> "enabled"
        }
        val conditions = flag.conditionCount
        if (conditions > 0) parts += "· ${plural(conditions, "condition")}"
        return Presentation(parts.joinToString(" "), HintColor.ENABLED)
    }

    internal fun plural(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"

    /** `50` for 50.0, `33.33` for 33.33. */
    internal fun percent(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
