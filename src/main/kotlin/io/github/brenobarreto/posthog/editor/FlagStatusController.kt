package io.github.brenobarreto.posthog.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm
import io.github.brenobarreto.posthog.cache.ProjectDataListener
import io.github.brenobarreto.posthog.cache.ProjectDataService
import io.github.brenobarreto.posthog.detect.ScanLanguage
import io.github.brenobarreto.posthog.settings.DisplayMode
import io.github.brenobarreto.posthog.settings.PostHogSettings
import io.github.brenobarreto.posthog.settings.PostHogSettingsListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Font

/** A hint that is currently in the editor: its inlay, its key (tracked through edits) and the underline, if any. */
internal class Applied(
    val spec: HintSpec,
    val inlay: Inlay<FlagHintRenderer>?,
    val keyRange: RangeMarker,
    val underline: RangeHighlighter?,
) {
    fun matches(other: HintSpec): Boolean =
        keyRange.isValid && keyRange.startOffset == other.keyStart && keyRange.endOffset == other.keyEnd &&
            inlay != null && inlay.isValid && inlay.offset == other.lineEnd &&
            inlay.renderer.presentation == other.presentation &&
            (underline != null) == !other.known

    fun dispose() {
        inlay?.dispose()
        underline?.dispose()
        keyRange.dispose()
    }
}

/**
 * Shows flag status in one editor. Works on the editor itself (inlays and highlighters), so it doesn't
 * depend on any language plugin; the file only has to be one the scanner handles.
 *
 * Flow: a change (typing, new data, new settings) restarts a 200 ms debounce; then the document text is
 * scanned off the EDT, and the result is applied on the EDT only if the document hasn't changed since.
 */
internal class FlagStatusController(
    private val project: Project,
    private val editor: Editor,
    private val language: ScanLanguage,
) : Disposable {
    private val service = project.service<ProjectDataService>()
    private val settings = PostHogSettings.getInstance()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var scanJob: Job? = null
    private var disposed = false

    /** Result of the last scan and the document stamp it belongs to. */
    private var specs: List<HintSpec> = emptyList()
    private var specsStamp = -1L

    private val applied = ArrayList<Applied>()

    private val hover = FlagHoverController(project, editor, this, targets = { applied })

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleUpdate()
        }, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = onCaretMoved()
        }, this)
        project.messageBus.connect(this).subscribe(ProjectDataListener.TOPIC, ProjectDataListener { scheduleUpdate() })
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(PostHogSettingsListener.TOPIC, PostHogSettingsListener { scheduleUpdate() })
        scheduleUpdate()
    }

    /** Safe to call from any thread. */
    fun scheduleUpdate() {
        if (disposed) return
        alarm.cancelAllRequests()
        alarm.addRequest(::startScan, DEBOUNCE_MS)
    }

    private fun startScan() {
        if (disposed || editor.isDisposed) return
        scanJob?.cancel()
        val config = settings.snapshot()
        val document = editor.document
        // Nothing is shown until the project's flags have loaded, or "not in PostHog" would be wrong.
        if (!config.showHints || !service.flags.isLoaded || document.textLength > MAX_CHARS) {
            specs = emptyList()
            specsStamp = document.modificationStamp
            applyVisible()
            return
        }
        val text = document.immutableCharSequence
        val stamp = document.modificationStamp
        scanJob = service.scope.launch(Dispatchers.Default) {
            val result = FlagHints.compute(
                text, language, service.flags, service.experiments, config.extraClientNames, config.extraFlagFunctions,
            )
            withContext(Dispatchers.EDT) {
                if (!disposed && !editor.isDisposed && document.modificationStamp == stamp) {
                    specs = result
                    specsStamp = stamp
                    applyVisible()
                }
            }
        }
    }

    private fun onCaretMoved() {
        // Specs from before an edit have stale lines; the pending rescan will apply fresh ones.
        if (settings.state.mode == DisplayMode.CURRENT_LINE && specsStamp == editor.document.modificationStamp) applyVisible()
    }

    private fun applyVisible() {
        val state = settings.state
        val visible = when {
            !state.showHints -> emptyList()
            state.mode == DisplayMode.ALWAYS -> specs
            else -> {
                val lines = editor.caretModel.allCarets.map { it.logicalPosition.line }.toSet()
                specs.filter { it.line in lines }
            }
        }
        if (applied.size == visible.size && applied.indices.all { applied[it].matches(visible[it]) }) return
        clear()
        visible.forEach(::add)
    }

    private fun add(spec: HintSpec) {
        val renderer = FlagHintRenderer(spec.presentation)
        val inlay = editor.inlayModel.addAfterLineEndElement(spec.lineEnd, true, renderer)
        val keyRange = editor.document.createRangeMarker(spec.keyStart, spec.keyEnd)
        val underline = if (spec.known) null else {
            val color = FlagHintRenderer.color(editor, HintColor.WARNING)
            editor.markupModel.addRangeHighlighter(
                spec.keyStart, spec.keyEnd, HighlighterLayer.WARNING,
                TextAttributes(null, null, color, EffectType.WAVE_UNDERSCORE, Font.PLAIN),
                HighlighterTargetArea.EXACT_RANGE,
            ).also {
                it.setErrorStripeMarkColor(color)
                it.putUserData(UNDERLINE_KEY, true)
            }
        }
        applied += Applied(spec, inlay, keyRange, underline)
    }

    private fun clear() {
        hover.hide()
        applied.forEach(Applied::dispose)
        applied.clear()
    }

    override fun dispose() {
        disposed = true
        scanJob?.cancel()
        clear()
    }

    companion object {
        const val DEBOUNCE_MS = 200

        /** Larger files (bundles, generated code) are skipped. */
        const val MAX_CHARS = 3_000_000

        val UNDERLINE_KEY = Key.create<Boolean>("posthog.flag.underline")
    }
}
