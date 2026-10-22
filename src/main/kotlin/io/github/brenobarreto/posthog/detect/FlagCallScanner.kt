package io.github.brenobarreto.posthog.detect

/** A feature flag check found in source text. Offsets index the scanned text. */
data class FlagCall(
    /** The SDK method, such as `getFeatureFlag`. */
    val method: String,
    /** The flag key, with escapes resolved. Never empty. */
    val key: String,
    /** Start of the key's string literal, including its opening quote. */
    val keyStart: Int,
    /** End (exclusive) of the key's string literal, including its closing quote. */
    val keyEnd: Int,
)

enum class ScanLanguage {
    JAVASCRIPT, JAVA, KOTLIN;

    companion object {
        /** The language to scan [fileName] as, or null when the plugin doesn't handle the file type. */
        fun forFileName(fileName: String): ScanLanguage? = when (fileName.substringAfterLast('.', "").lowercase()) {
            "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts" -> JAVASCRIPT
            "java" -> JAVA
            "kt", "kts" -> KOTLIN
            else -> null
        }
    }
}

class ScanOptions(
    /** Receiver names recognised in addition to the built-in ones (`posthog`, `client`, ...). */
    val extraClientNames: Set<String> = emptySet(),
    /** Bare functions whose first argument is a flag key, in addition to hooks like `useFeatureFlag`. */
    val extraFlagFunctions: Set<String> = emptySet(),
    /**
     * Whether PostHog knows the key. Only used to settle Java/Kotlin calls where both of the first two
     * arguments are string literals and the SDK can't be told from the argument order alone.
     */
    val isKnownKey: (String) -> Boolean = { false },
)

/**
 * Finds flag checks in source text. Implementations must work on the raw text of any file the plugin
 * handles, so the plugin doesn't depend on a language plugin being installed; a PSI-based one can
 * replace or complement the text scanner later.
 */
interface FlagCallScanner {
    /** The calls whose flag key is a string literal, in source order. Calls with computed keys are ignored. */
    fun scan(text: CharSequence, language: ScanLanguage, options: ScanOptions = ScanOptions()): List<FlagCall>
}
