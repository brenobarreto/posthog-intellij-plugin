package io.github.brenobarreto.posthog.detect

import io.github.brenobarreto.posthog.detect.ScanLanguage.JAVA
import io.github.brenobarreto.posthog.detect.ScanLanguage.JAVASCRIPT
import io.github.brenobarreto.posthog.detect.ScanLanguage.KOTLIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** One row per rule from PLAN.md's "Call detection rules", plus the lexing edge cases behind them. */
@RunWith(Parameterized::class)
class ScannerRulesTest(private val case: Case) {

    class Case(
        val name: String,
        val language: ScanLanguage,
        val source: String,
        val expected: List<String>,
        val options: ScanOptions = ScanOptions(),
    ) {
        override fun toString() = name
    }

    @Test
    fun `finds exactly the expected keys`() {
        val calls = TextFlagCallScanner.scan(case.source, case.language, case.options)
        assertEquals(case.expected, calls.map { it.key })
        calls.forEach { call -> // the range covers the whole literal, quotes included
            val literal = case.source.substring(call.keyStart, call.keyEnd)
            assertTrue(literal, literal.first() in "'\"`" && literal.last() == literal.first())
        }
    }

    companion object {
        private const val D = "$"

        private fun js(name: String, source: String, vararg expected: String, options: ScanOptions = ScanOptions()) =
            Case("js: $name", JAVASCRIPT, source, expected.toList(), options)

        private fun kt(name: String, source: String, vararg expected: String, options: ScanOptions = ScanOptions()) =
            Case("kt: $name", KOTLIN, source, expected.toList(), options)

        private fun java(name: String, source: String, vararg expected: String, options: ScanOptions = ScanOptions()) =
            Case("java: $name", JAVA, source, expected.toList(), options)

        private fun knowing(vararg keys: String) = ScanOptions(isKnownKey = { it in keys })

        private val JS_AND_TS = listOf(
            js("every method", """
                posthog.getFeatureFlag('a'); posthog.isFeatureEnabled('b'); posthog.getFeatureFlagPayload('c')
                posthog.getFeatureFlagResult('d'); posthog.isFeatureFlagEnabled('e'); posthog.getRemoteConfig('f')
            """.trimIndent(), "a", "b", "c", "d", "e", "f"),
            js("every default receiver", "postHog.getFeatureFlag('a'); PostHog.getFeatureFlag('b'); client.getFeatureFlag('c'); ph.getFeatureFlag('d')", "a", "b", "c", "d"),
            js("unknown receiver", "other.getFeatureFlag('no'); posthogish.isFeatureEnabled('no'); getFeatureFlag('no')"),
            js("nested receivers", "window.posthog.getFeatureFlag('a'); this.posthog?.getFeatureFlag('b'); props.client.isFeatureEnabled('c'); posthog!.getFeatureFlag('d')", "a", "b", "c", "d"),
            js("optional call", "posthog?.isFeatureEnabled('x')", "x"),
            js("call result as receiver", "getClient().getFeatureFlag('no'); usePostHog().getFeatureFlag('no')"),
            js("quote styles", "posthog.getFeatureFlag(\"double\"); posthog.getFeatureFlag('single'); posthog.getFeatureFlag(`template`)", "double", "single", "template"),
            js("escapes in the key", "posthog.getFeatureFlag('it\\'s'); posthog.getFeatureFlag(\"a\\u002Db\"); posthog.getFeatureFlag('tab\\there')", "it's", "a-b", "tab\there"),
            js("template with interpolation", "posthog.getFeatureFlag(`flag-${D}{id}`)"),
            js("call inside an interpolation", "const s = `value: ${D}{posthog.getFeatureFlag('inner')} done`; posthog.getFeatureFlag('outer')", "inner", "outer"),
            js("nested template literals", "const s = `a ${D}{`b ${D}{posthog.isFeatureEnabled('deep')}`} c`", "deep"),
            js("computed keys are ignored", "posthog.getFeatureFlag(KEY); posthog.getFeatureFlag('a' + b); posthog.getFeatureFlag(cond ? 'x' : 'y'); posthog.getFeatureFlag(flags.name)"),
            js("empty key is skipped", "posthog.getFeatureFlag(''); posthog.isFeatureEnabled(\"\")"),
            js("extra arguments", "posthog.getFeatureFlag('a', { send_event: false }); client.getFeatureFlag('b', user.id); posthog.getFeatureFlag('c', {x: [1, 2], y: f(1, 2)})", "a", "b", "c"),
            js("arguments over several lines", "posthog.getFeatureFlag(\n  'multi-line',\n  { send_event: false },\n)", "multi-line"),
            js("two calls on a line", "const a = posthog.getFeatureFlag('one'), b = posthog.getFeatureFlag('two')", "one", "two"),
            js("typescript type arguments", "posthog.getFeatureFlag<string>('typed'); posthog.getFeatureFlagPayload<Record<string, number>>('record')", "typed", "record"),
            js("jsx expression", "{posthog.isFeatureEnabled('jsx-flag') && <Banner />}", "jsx-flag"),
            js("line comment", "// posthog.getFeatureFlag('commented')\nposthog.getFeatureFlag('real')", "real"),
            js("block comment", "/* posthog.getFeatureFlag('c1') */ posthog.getFeatureFlag('after') /** posthog.isFeatureEnabled('c2') */", "after"),
            js("code in a string", "const s = \"posthog.getFeatureFlag('x')\"; const t = 'posthog.isFeatureEnabled(\"y\")'"),
            js("slashes in a string are not a comment", "const url = \"http://example.test\"; posthog.getFeatureFlag('after-url')", "after-url"),
            js("question mark dot before a number", "const v = ok ?.5 : 1; posthog.getFeatureFlag('after-ternary')", "after-ternary"),
            js("not a call", "const f = posthog.getFeatureFlag; f('no'); posthog.getFeatureFlag"),
            js("bare method name", "isFeatureEnabled('no'); class A { getFeatureFlag('no') {} }"),
            js("unterminated string ends at the line", "posthog.getFeatureFlag('abc\nposthog.isFeatureEnabled('next')", "next"),
            js("call still being typed", "posthog.getFeatureFlag('typing'", "typing"),
            js("hooks", "useFeatureFlag('a'); useFeatureFlagPayload('b'); useFeatureFlagVariantKey('c')", "a", "b", "c"),
            js("hook used as a method is not a hook", "foo.useFeatureFlag('no')"),
            js("hook declaration", "function useFeatureFlag(key = 'default') {}"),
            js("extra flag functions", "useFlag('a'); posthog.getFeatureFlag('b')", "a", "b", options = ScanOptions(extraFlagFunctions = setOf("useFlag"))),
            js("extra flag functions off by default", "useFlag('a')"),
            js("extra client names", "analytics.getFeatureFlag('a'); sut.isFeatureEnabled('b')", "a", "b", options = ScanOptions(extraClientNames = setOf("analytics", "sut"))),
            js("garbage around a call", "))) } ]] posthog.getFeatureFlag('ok') (( {{", "ok"),
            js("empty text", ""),
        )

        private val KOTLIN_AND_JAVA = listOf(
            // Android / client SDK: key first
            kt("android sample", "PostHog.isFeatureEnabled(\"enable_network_request\", false)", "enable_network_request"),
            kt("android with a string default", "PostHog.getFeatureFlag(\"splashScreenName\", \"default\")", "splashScreenName"),
            kt("android every method", "posthog.getFeatureFlag(\"a\"); posthog.isFeatureEnabled(\"b\"); posthog.getFeatureFlagPayload(\"c\"); posthog.getFeatureFlagResult(\"d\")", "a", "b", "c", "d"),
            kt("android default is a variable", "posthog.getFeatureFlag(\"key\", fallback)", "key"),
            // server SDK: distinct ID first
            kt("server with a variable distinct id", "client.isFeatureEnabled(userId, \"feature_key\")", "feature_key"),
            kt("server with options", "client.getFeatureFlag(distinctId, \"blue\", options)", "blue"),
            kt("server key is a variable", "client.getFeatureFlag(\"user123\", KEY)", "user123"),
            // both literal: the cache decides, then the SDK shapes
            kt("both literal: first is known", "client.getFeatureFlag(\"flag\", \"default\")", "flag", options = knowing("flag")),
            kt("both literal: second is known", "client.getFeatureFlag(\"user123\", \"feature_key\")", "feature_key", options = knowing("feature_key")),
            kt("both literal and both known", "client.getFeatureFlag(\"a\", \"b\")", "a", options = knowing("a", "b")),
            kt("both literal and unknown: isFeatureEnabled means server", "client.isFeatureEnabled(\"user123\", \"missing\")", "missing"),
            kt("both literal and unknown: others take the first", "client.getFeatureFlag(\"flag\", \"default\")", "flag"),
            kt("both literal and unknown: payload", "client.getFeatureFlagPayload(\"a\", \"b\")", "a"),
            kt("literal and boolean", "posthog.isFeatureEnabled(\"flag\", true)", "flag"),
            // named arguments
            kt("named key", "client.isFeatureEnabled(distinctId = \"u\", key = \"named\")", "named"),
            kt("named key not first", "PostHog.getFeatureFlag(defaultValue = \"x\", key = \"k\")", "k"),
            kt("named key wins over positional", "client.getFeatureFlag(\"user\", key = \"flag\")", "flag"),
            kt("named key that isn't a literal blocks the positional one", "client.isFeatureEnabled(\"user\", key = KEY)"),
            kt("comparison is not a named argument", "client.isFeatureEnabled(key == \"x\")"),
            // stateless
            kt("stateless takes the second argument", "client.isFeatureEnabledStateless(\"user123\", \"test_flag\")", "test_flag"),
            kt("stateless every method", "client.getFeatureFlagStateless(id, \"a\"); client.getFeatureFlagPayloadStateless(id, \"b\"); client.getFeatureFlagResultStateless(id, \"c\")", "a", "b", "c"),
            kt("stateless with a computed key", "client.getFeatureFlagStateless(\"user\", KEY)"),
            // receivers
            kt("kotlin safe calls", "posthog?.isFeatureEnabled(\"safe\", false); posthog!!.isFeatureEnabled(\"bang\", false)", "safe", "bang"),
            java("companion from java", "PostHog.Companion.isFeatureEnabled(\"viaCompanion\", false);", "viaCompanion"),
            java("java receiver", "posthog.getFeatureFlag(\"java-flag\");", "java-flag"),
            kt("unknown receiver", "flags.isFeatureEnabled(\"no\", false); sut.getFeatureFlag(\"no\")"),
            kt("extra client names", "sut.getFeatureFlag(\"yes\")", "yes", options = ScanOptions(extraClientNames = setOf("sut"))),
            kt("extra flag functions", "flagEnabled(\"x\")", "x", options = ScanOptions(extraFlagFunctions = setOf("flagEnabled"))),
            kt("no react hooks on the jvm", "useFeatureFlag(\"no\")"),
            // evaluateFlags (server SDK)
            kt("evaluateFlags chain", "posthog.evaluateFlags(distinctId).isEnabled(\"chain\")", "chain"),
            kt("evaluateFlags chain with options", "posthog.evaluateFlags(distinctId, options).isEnabled(\"file-previews\")", "file-previews"),
            kt("evaluateFlags chain over lines", "posthog.evaluateFlags(id)\n    .getFlag(\"multi\")", "multi"),
            kt("evaluateFlags variable", "val flags = posthog.evaluateFlags(id)\nflags.isEnabled(\"a\")\nflags.getFlag(\"b\")\nflags.getFlagPayload(\"c\")", "a", "b", "c"),
            kt("evaluateFlags typed variable", "val f: PostHogFeatureFlagEvaluations = posthog.evaluateFlags(id)\nf.isEnabled(\"typed\")", "typed"),
            kt("evaluateFlags generic payload", "val flags = posthog.evaluateFlags(id)\nval p = flags.getFlagPayloadAs<String>(\"gen\")", "gen"),
            java("evaluateFlags in java", "PostHogFeatureFlagEvaluations flags = posthog.evaluateFlags(distinctId);\nboolean on = flags.isEnabled(\"beta-feature\");\nflags.getFlagPayloadAs(\"payload\", Object.class);", "beta-feature", "payload"),
            kt("isEnabled on something else", "other.isEnabled(\"no\"); val x = compute(id); x.isEnabled(\"no\")"),
            kt("evaluateFlags variable is only known after its assignment line", "flags.isEnabled(\"no\")"),
            // strings and comments
            kt("kotlin templates", "posthog.isFeatureEnabled(\"flag-${D}suffix\", false); posthog.isFeatureEnabled(\"${D}{prefix}-x\", false); posthog.isFeatureEnabled(\"plain\", false)", "plain"),
            kt("escaped dollar is plain text", "posthog.isFeatureEnabled(\"price\\${D}5\", false)", "price$5"),
            kt("kotlin raw string", "posthog.isFeatureEnabled(\"\"\"raw-key\"\"\", false)", "raw-key"),
            java("dollar is plain text in java", "posthog.isFeatureEnabled(\"price${D}5\", false);", "price${D}5"),
            kt("nested block comments", "/* outer /* inner */ posthog.getFeatureFlag(\"hidden\") */ posthog.getFeatureFlag(\"visible\")", "visible"),
            java("block comments do not nest in java", "/* outer /* inner */ posthog.getFeatureFlag(\"visible\"); /* */", "visible"),
            kt("char literal quote", "val q = '\"'; posthog.isFeatureEnabled(\"after-char\", false)", "after-char"),
            java("char literal quote in java", "char q = '\"'; posthog.getFeatureFlag(\"after-char\");", "after-char"),
            kt("backtick function name with an apostrophe", "fun `doesn't break parsing`() { posthog.isFeatureEnabled(\"after-backtick\", false) }", "after-backtick"),
            kt("code in a string", "val s = \"posthog.isFeatureEnabled(\\\"x\\\", false)\""),
            kt("declarations are not calls", "fun isFeatureEnabled(key: String, defaultValue: Boolean = false): Boolean\noverride fun getFeatureFlag(key: String) = null"),
            kt("arguments over several lines", "posthog.isFeatureEnabled(\n    \"multi\",\n    false,\n)", "multi"),
            kt("computed keys are ignored", "posthog.isFeatureEnabled(KEY, false); posthog.getFeatureFlag(\"a\" + b)"),
            kt("empty key is skipped", "posthog.isFeatureEnabled(\"\", false)"),
            kt("call still being typed", "posthog.isFeatureEnabled(\"typing\"", "typing"),
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = (JS_AND_TS + KOTLIN_AND_JAVA).map { arrayOf(it) }
    }
}
