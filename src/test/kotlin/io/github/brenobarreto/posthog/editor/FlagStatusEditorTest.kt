package io.github.brenobarreto.posthog.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.brenobarreto.posthog.api.Experiment
import io.github.brenobarreto.posthog.api.FeatureFlag
import io.github.brenobarreto.posthog.cache.DataKind
import io.github.brenobarreto.posthog.cache.ProjectDataListener
import io.github.brenobarreto.posthog.cache.ProjectDataService
import io.github.brenobarreto.posthog.settings.DisplayMode
import io.github.brenobarreto.posthog.settings.PostHogSettings

/**
 * Opens real editors with the flag cache seeded and checks what ends up in them. The files are `.js`,
 * `.kt` and `.java`, which this test IDE (no language plugins) treats as plain text, which is the
 * situation of the free editions the plugin has to work in.
 */
class FlagStatusEditorTest : BasePlatformTestCase() {
    private val data get() = project.service<ProjectDataService>().data
    private val settings get() = PostHogSettings.getInstance()

    override fun tearDown() {
        try {
            data.clear()
            settings.loadState(PostHogSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun load(flags: List<FeatureFlag>, experiments: List<Experiment> = emptyList()) {
        data.flags.update(flags, 0)
        data.experiments.update(experiments, 0)
        notifyChanged()
    }

    private fun notifyChanged() = project.messageBus.syncPublisher(ProjectDataListener.TOPIC).dataChanged(DataKind.FLAGS)

    private fun open(name: String, text: String): Editor = myFixture.configureByText(name, text).let { myFixture.editor }

    private fun hints(editor: Editor = myFixture.editor): List<String> =
        editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength)
            .sortedBy { it.offset }.map { (it.renderer as FlagHintRenderer).presentation.text }

    private fun underlines(editor: Editor = myFixture.editor) =
        editor.markupModel.allHighlighters.filter { it.getUserData(FlagStatusController.UNDERLINE_KEY) == true }

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what; hints=${hints()}" }
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
    }

    /** Lets pending work run, for checking that nothing happens. */
    private fun settle(ms: Long = 700) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
    }

    fun testShowsEachFlagsStatusAtTheEndOfItsLine() {
        load(listOf(flag("beta", rollout = 50.0), flag("old", active = false)))
        val editor = open("app.js", "if (posthog.isFeatureEnabled('beta')) {\n  run()\n}\nconst v = posthog.getFeatureFlag('old')\n")

        await("both hints") { hints().size == 2 }
        assertEquals(listOf("● 50%", "○ inactive"), hints())
        val inlayLines = editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength)
            .sortedBy { it.offset }.map { editor.document.getLineNumber(it.offset) }
        assertEquals(listOf(0, 3), inlayLines)
        assertTrue(underlines().isEmpty())
    }

    fun testUnknownFlagsGetAWarningAndAWavyUnderlineOnTheKey() {
        load(listOf(flag("known")))
        val editor = open("app.js", "posthog.getFeatureFlag('missing-flag')\n")

        await("warning") { hints() == listOf("⚠ not in PostHog") }
        val underline = underlines().single()
        assertEquals("'missing-flag'", editor.document.getText(com.intellij.openapi.util.TextRange(underline.startOffset, underline.endOffset)))
    }

    fun testKnownFlagsAreNotUnderlined() {
        load(listOf(flag("known")))
        open("app.js", "posthog.getFeatureFlag('known')\n")
        await("hint") { hints().size == 1 }
        assertTrue(underlines().isEmpty())
    }

    fun testNothingIsShownUntilFlagsHaveLoaded() {
        open("app.js", "posthog.getFeatureFlag('anything')\n")
        settle()
        assertTrue(hints().isEmpty())
        assertTrue(underlines().isEmpty())

        load(listOf(flag("anything", rollout = 100.0)))
        await("hint after load") { hints() == listOf("● 100%") }
    }

    fun testHintsFollowEditsToTheDocument() {
        load(listOf(flag("one", rollout = 10.0), flag("two", rollout = 20.0)))
        val editor = open("app.js", "posthog.getFeatureFlag('one')\n")
        await("first hint") { hints() == listOf("● 10%") }

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(editor.document.textLength, "posthog.isFeatureEnabled('two')\n")
        }
        await("second hint") { hints() == listOf("● 10%", "● 20%") }

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.replaceString(0, editor.document.getLineEndOffset(0) + 1, "")
        }
        await("first hint gone") { hints() == listOf("● 20%") }
    }

    fun testTwoChecksOnOneLineGetTwoHints() {
        load(listOf(flag("one", rollout = 10.0), flag("two", rollout = 20.0)))
        open("app.js", "const a = posthog.getFeatureFlag('one'), b = posthog.getFeatureFlag('two')\n")
        await("two hints") { hints() == listOf("● 10%", "● 20%") }
    }

    fun testALinkedExperimentChangesTheLabel() {
        load(listOf(flag("exp")), listOf(experiment("exp", start = "2026-01-01T00:00:00Z")))
        open("app.js", "posthog.getFeatureFlag('exp')\n")
        await("experiment hint") { hints() == listOf("⚗ experiment running") }
    }

    fun testNewDataReplacesTheHints() {
        load(listOf(flag("f", rollout = 10.0)))
        open("app.js", "posthog.getFeatureFlag('f')\n")
        await("first") { hints() == listOf("● 10%") }

        load(listOf(flag("f", rollout = 90.0)))
        await("updated") { hints() == listOf("● 90%") }
    }

    fun testSigningOutRemovesTheHints() {
        load(listOf(flag("f")))
        open("app.js", "posthog.getFeatureFlag('f')\n")
        await("hint") { hints().size == 1 }

        data.clear()
        await("hints removed") { hints().isEmpty() }
    }

    fun testTurningHintsOffAndOn() {
        load(listOf(flag("f", rollout = 10.0)))
        open("app.js", "posthog.getFeatureFlag('f')\n")
        await("hint") { hints().size == 1 }

        settings.state.showHints = false
        settings.notifyChanged()
        await("hidden") { hints().isEmpty() }

        settings.state.showHints = true
        settings.notifyChanged()
        await("shown again") { hints().size == 1 }
    }

    fun testCurrentLineModeOnlyShowsTheCaretLine() {
        settings.state.mode = DisplayMode.CURRENT_LINE
        load(listOf(flag("a", rollout = 10.0), flag("b", rollout = 20.0)))
        val editor = open("app.js", "posthog.getFeatureFlag('a')\nconst x = 1\nposthog.getFeatureFlag('b')\n")
        editor.caretModel.moveToLogicalPosition(LogicalPosition(0, 0))

        await("hint on line 0") { hints() == listOf("● 10%") }

        editor.caretModel.moveToLogicalPosition(LogicalPosition(1, 0))
        await("no hint on a line without a check") { hints().isEmpty() }

        editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 0))
        await("hint on line 2") { hints() == listOf("● 20%") }

        settings.state.mode = DisplayMode.ALWAYS
        settings.notifyChanged()
        await("all hints") { hints() == listOf("● 10%", "● 20%") }
    }

    fun testJavaAndKotlinFilesAreScannedByTheirExtension() {
        load(listOf(flag("feature_key", rollout = 100.0)))
        open("Flags.kt", "val on = client.isFeatureEnabled(userId, \"feature_key\")\n")
        await("kotlin hint") { hints() == listOf("● 100%") }

        open("Flags.java", "boolean on = client.isFeatureEnabled(userId, \"feature_key\");\n")
        await("java hint") { hints() == listOf("● 100%") }
    }

    fun testBothLiteralCallsAreSettledByTheCache() {
        load(listOf(flag("feature_key", rollout = 100.0)))
        open("Flags.kt", "client.getFeatureFlag(\"user123\", \"feature_key\")\n")
        await("hint") { hints() == listOf("● 100%") }
        assertTrue(underlines().isEmpty())
    }

    fun testOtherFileTypesAreLeftAlone() {
        load(listOf(flag("f")))
        open("notes.txt", "posthog.getFeatureFlag('missing')\n")
        settle()
        assertTrue(hints().isEmpty())
        assertTrue(underlines().isEmpty())
    }

    fun testHintsTakeUpRoomAfterTheLine() {
        load(listOf(flag("f", rollout = 10.0)))
        val editor = open("app.js", "posthog.getFeatureFlag('f')\n")
        await("hint") { hints().size == 1 }

        val inlay = editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength).single()
        assertTrue("width ${inlay.widthInPixels}", inlay.widthInPixels > 20)
    }

    fun testNothingBreaksWhenTheTextHasNoChecks() {
        load(listOf(flag("f")))
        open("app.js", "const x = 1;\nfunction f() { return x }\n")
        settle()
        assertTrue(hints().isEmpty())
    }
}
