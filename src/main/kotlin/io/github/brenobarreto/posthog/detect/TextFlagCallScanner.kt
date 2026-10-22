package io.github.brenobarreto.posthog.detect

/**
 * Finds flag checks by scanning tokens, using the rules in PLAN.md ("Call detection rules"). Receiver
 * names and the JavaScript method list follow posthog-vscode's tree-sitter detection (MIT; see NOTICE);
 * the Java/Kotlin rules come from the Android and server SDKs' method signatures.
 */
object TextFlagCallScanner : FlagCallScanner {
    override fun scan(text: CharSequence, language: ScanLanguage, options: ScanOptions): List<FlagCall> =
        Scan(Lexer(text, language).lex(), language, options).run()

    private val DEFAULT_RECEIVERS = setOf("posthog", "postHog", "PostHog", "client", "ph")

    private val JS_METHODS = setOf(
        "getFeatureFlag", "isFeatureEnabled", "getFeatureFlagPayload",
        "getFeatureFlagResult", "isFeatureFlagEnabled", "getRemoteConfig",
    )
    private val JS_HOOKS = setOf("useFeatureFlag", "useFeatureFlagPayload", "useFeatureFlagVariantKey")

    /** Same names in the Android SDK (key first) and the server SDK (distinct ID first). */
    private val JVM_METHODS = setOf(
        "isFeatureEnabled", "getFeatureFlag", "getFeatureFlagPayload", "getFeatureFlagResult",
        "isFeatureEnabledStateless", "getFeatureFlagStateless", "getFeatureFlagPayloadStateless", "getFeatureFlagResultStateless",
    )

    /** Methods of the server SDK's `evaluateFlags(...)` result; the key is the first argument. */
    private val EVALUATION_METHODS = setOf("isEnabled", "getFlag", "getFlagPayload", "getFlagPayloadAs")

    private const val MAX_ARG_TOKENS = 5_000

    private class Arg(val first: Int, val last: Int)

    private class ParsedArgs(val args: List<Arg>, val close: Int)

    private class Scan(private val t: List<Token>, private val language: ScanLanguage, private val options: ScanOptions) {
        private val isJs = language == ScanLanguage.JAVASCRIPT
        private val receivers = DEFAULT_RECEIVERS + options.extraClientNames
        private val bareFunctions = (if (isJs) JS_HOOKS else emptySet()) + options.extraFlagFunctions

        // Closing parentheses of `evaluateFlags(...)` calls, and variables that hold their result.
        private val evaluationCloses = HashSet<Int>()
        private val evaluationVariables = HashSet<String>()

        fun run(): List<FlagCall> {
            if (!isJs) findEvaluations()
            val calls = ArrayList<FlagCall>()
            for (i in t.indices) {
                val name = t[i]
                if (name.kind != TokenKind.IDENT) continue
                val open = openParen(i) ?: continue
                val keyToken = if (isDot(i - 1)) memberKey(i, open) else bareKey(i, open)
                if (keyToken == null || keyToken.text.isEmpty()) continue
                calls += FlagCall(name.text, keyToken.text, keyToken.start, keyToken.end)
            }
            return calls
        }

        private fun memberKey(nameIdx: Int, open: Int): Token? {
            val method = t[nameIdx].text
            val receiver = receiverName(nameIdx - 1)
            if (isJs) {
                if (method !in JS_METHODS || receiver !in receivers) return null
                return literal(parseArgs(open).args.getOrNull(0))
            }
            val args = parseArgs(open).args
            if (method in JVM_METHODS && receiver in receivers) return jvmKey(method, args)
            if (method in EVALUATION_METHODS && (receiver in evaluationVariables || isEvaluationChain(nameIdx - 1))) {
                return namedKey(args) ?: literal(args.getOrNull(0))
            }
            return null
        }

        private fun bareKey(nameIdx: Int, open: Int): Token? {
            if (t[nameIdx].text !in bareFunctions) return null
            return literal(parseArgs(open).args.getOrNull(0))
        }

        /** Java/Kotlin: which of the first two arguments is the key (PLAN.md, "Java/Kotlin key ambiguity"). */
        private fun jvmKey(method: String, args: List<Arg>): Token? {
            // 1. A Kotlin named argument `key = ...` settles it, even when its value isn't a literal.
            if (args.any(::isNamedKey)) return namedKey(args)
            // 2. Stateless variants always take the distinct ID first.
            if (method.endsWith("Stateless")) return literal(args.getOrNull(1))
            val first = literal(args.getOrNull(0))
            val second = literal(args.getOrNull(1))
            return when {
                first == null -> second // 3. only one literal
                second == null -> first
                else -> { // 4. both literals: ask the cache, then fall back on the SDKs' shapes
                    val firstKnown = options.isKnownKey(first.text)
                    val secondKnown = options.isKnownKey(second.text)
                    when {
                        firstKnown && !secondKnown -> first
                        secondKnown && !firstKnown -> second
                        // The client SDK's second argument is a Boolean, so a string there means the server SDK.
                        method == "isFeatureEnabled" -> second
                        else -> first
                    }
                }
            }
        }

        private fun isNamedKey(arg: Arg): Boolean =
            language == ScanLanguage.KOTLIN && arg.last - arg.first >= 1 &&
                t[arg.first].kind == TokenKind.IDENT && t[arg.first].text == "key" &&
                isPunct(arg.first + 1, "=") && !isPunct(arg.first + 2, "=")

        private fun namedKey(args: List<Arg>): Token? {
            val arg = args.firstOrNull(::isNamedKey) ?: return null
            return if (arg.last - arg.first == 2) t[arg.last].takeIf { it.kind == TokenKind.STRING } else null
        }

        /** The argument's token when it is exactly one string literal. */
        private fun literal(arg: Arg?): Token? {
            if (arg == null || arg.first != arg.last) return null
            return t[arg.first].takeIf { it.kind == TokenKind.STRING }
        }

        /** The identifier a method is called on: `posthog` in `posthog.m()`, `window.posthog?.m()` or `posthog!!.m()`. */
        private fun receiverName(dotIdx: Int): String? {
            var j = dotIdx - 1
            while (isPunct(j, "!")) j--
            val token = t.getOrNull(j)?.takeIf { it.kind == TokenKind.IDENT } ?: return null
            // Java reaches Kotlin's `object PostHog` through `PostHog.Companion`.
            if (!isJs && token.text == "Companion" && isDot(j - 1)) {
                return t.getOrNull(j - 2)?.takeIf { it.kind == TokenKind.IDENT }?.text
            }
            return token.text
        }

        private fun isEvaluationChain(dotIdx: Int): Boolean = (dotIdx - 1) in evaluationCloses

        /** Finds `evaluateFlags(...)` calls so that `evaluateFlags(id).isEnabled("k")` and `flags.isEnabled("k")` are seen. */
        private fun findEvaluations() {
            for (i in t.indices) {
                if (t[i].kind != TokenKind.IDENT || t[i].text != "evaluateFlags") continue
                val open = openParen(i) ?: continue
                val close = parseArgs(open).close
                if (close >= 0) evaluationCloses += close
                assignedVariable(i)?.let(evaluationVariables::add)
            }
        }

        /** The variable in `[val|Type] name [: Type] = [receiver.]evaluateFlags(`, if the call is assigned. */
        private fun assignedVariable(callIdx: Int): String? {
            var j = callIdx - 1
            while (j >= 0 && (isDot(j) || isPunct(j, "!") || (t[j].kind == TokenKind.IDENT && isDot(j + 1)))) j--
            if (!isPunct(j, "=") || isPunct(j - 1, "=") || isPunct(j - 1, "!")) return null
            val last = t.getOrNull(j - 1)?.takeIf { it.kind == TokenKind.IDENT } ?: return null
            if (isPunct(j - 2, ":")) return t.getOrNull(j - 3)?.takeIf { it.kind == TokenKind.IDENT }?.text // name: Type =
            return last.text
        }

        /** The `(` that opens the call whose name is at [nameIdx], allowing explicit type arguments like `<String>`. */
        private fun openParen(nameIdx: Int): Int? {
            var j = nameIdx + 1
            if (isPunct(j, "<")) {
                var depth = 0
                val limit = minOf(t.size, j + 24)
                while (j < limit) {
                    val token = t[j]
                    when {
                        isPunct(j, "<") -> depth++
                        isPunct(j, ">") -> if (--depth == 0) {
                            j++
                            break
                        }
                        token.kind == TokenKind.IDENT -> Unit
                        token.kind == TokenKind.PUNCT && token.text in TYPE_ARGUMENT_PUNCTUATION -> Unit
                        else -> return null
                    }
                    j++
                }
                if (depth != 0) return null
            }
            return if (isPunct(j, "(")) j else null
        }

        /**
         * Splits the arguments of the call opened at [open]. A call that never closes (code being typed)
         * still yields the arguments seen so far, with `close` = -1.
         */
        private fun parseArgs(open: Int): ParsedArgs {
            val args = ArrayList<Arg>()
            var depth = 0
            var argStart = open + 1
            var j = open + 1
            val limit = minOf(t.size, open + 1 + MAX_ARG_TOKENS)
            while (j < limit) {
                val token = t[j]
                if (token.kind == TokenKind.PUNCT) {
                    when (token.text) {
                        "(", "[", "{" -> depth++
                        ")", "]", "}" -> {
                            if (depth > 0) {
                                depth--
                            } else if (token.text == ")") {
                                args += Arg(argStart, j - 1)
                                return ParsedArgs(args, j)
                            } else {
                                break // left the enclosing block: the call was never closed
                            }
                        }
                        "," -> if (depth == 0) {
                            args += Arg(argStart, j - 1)
                            argStart = j + 1
                        }
                    }
                }
                j++
            }
            if (argStart < j) args += Arg(argStart, j - 1)
            return ParsedArgs(args, -1)
        }

        private fun isPunct(idx: Int, text: String) = t.getOrNull(idx)?.let { it.kind == TokenKind.PUNCT && it.text == text } == true

        private fun isDot(idx: Int) = isPunct(idx, ".") || isPunct(idx, "?.")
    }

    private val TYPE_ARGUMENT_PUNCTUATION = setOf(",", ".", "?", "*", "[", "]", "&", "|", ":")
}
