package io.github.brenobarreto.posthog.editor

import io.github.brenobarreto.posthog.cache.ExperimentCache
import io.github.brenobarreto.posthog.cache.FlagCache
import io.github.brenobarreto.posthog.detect.FlagCallScanner
import io.github.brenobarreto.posthog.detect.ScanLanguage
import io.github.brenobarreto.posthog.detect.ScanOptions
import io.github.brenobarreto.posthog.detect.TextFlagCallScanner

/** One hint to draw. Offsets and [line] describe the text that was scanned. */
data class HintSpec(
    val key: String,
    val method: String,
    val keyStart: Int,
    val keyEnd: Int,
    /** Zero-based line of the key; the hint goes at the end of this line. */
    val line: Int,
    val lineEnd: Int,
    val presentation: Presentation,
    /** False when PostHog has no such flag; the key then gets a wavy underline. */
    val known: Boolean,
)

object FlagHints {
    /**
     * Scans [text] and decides what to show for each flag check. Pure and thread-safe, so it can run off
     * the EDT on an immutable snapshot of the document.
     */
    fun compute(
        text: CharSequence,
        language: ScanLanguage,
        flags: FlagCache,
        experiments: ExperimentCache,
        extraClientNames: Set<String> = emptySet(),
        extraFlagFunctions: Set<String> = emptySet(),
        scanner: FlagCallScanner = TextFlagCallScanner,
    ): List<HintSpec> {
        val options = ScanOptions(extraClientNames, extraFlagFunctions) { flags.flag(it) != null }
        val specs = ArrayList<HintSpec>()
        var line = 0
        var pos = 0
        for (call in scanner.scan(text, language, options)) {
            while (pos < call.keyStart) if (text[pos++] == '\n') line++
            var lineEnd = call.keyStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            val flag = flags.flag(call.key)
            specs += HintSpec(
                key = call.key, method = call.method, keyStart = call.keyStart, keyEnd = call.keyEnd,
                line = line, lineEnd = lineEnd,
                presentation = FlagPresentation.hint(flag, experiments.forFlag(call.key)),
                known = flag != null,
            )
        }
        return specs
    }
}
