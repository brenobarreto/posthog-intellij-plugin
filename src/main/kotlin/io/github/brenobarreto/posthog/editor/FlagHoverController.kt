package io.github.brenobarreto.posthog.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import io.github.brenobarreto.posthog.cache.ProjectDataService
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * The hover popup for a flag check, shown from the key or from the hint after it. The popup stays open
 * while the mouse is over it, so its links can be clicked.
 */
internal class FlagHoverController(
    private val project: Project,
    private val editor: Editor,
    parent: Disposable,
    private val targets: () -> List<Applied>,
) : EditorMouseMotionListener, EditorMouseListener {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private var popup: JBPopup? = null
    private var shownFor: Applied? = null
    private var mouseInPopup = false

    init {
        editor.addEditorMouseMotionListener(this, parent)
        editor.addEditorMouseListener(this, parent)
    }

    override fun mouseMoved(e: EditorMouseEvent) {
        val hit = hitTest(e)
        when {
            hit == null -> scheduleHide()
            hit === shownFor && popup?.isVisible == true -> alarm.cancelAllRequests()
            else -> {
                alarm.cancelAllRequests()
                alarm.addRequest({ show(hit) }, SHOW_DELAY_MS)
            }
        }
    }

    override fun mouseExited(event: EditorMouseEvent) = scheduleHide()

    fun hide() {
        alarm.cancelAllRequests()
        popup?.cancel()
        popup = null
        shownFor = null
        mouseInPopup = false
    }

    private fun scheduleHide() {
        alarm.cancelAllRequests()
        if (popup?.isVisible == true) alarm.addRequest({ if (!mouseInPopup) hide() }, HIDE_DELAY_MS)
    }

    private fun hitTest(e: EditorMouseEvent): Applied? {
        val list = targets()
        e.inlay?.let { inlay -> list.firstOrNull { it.inlay === inlay }?.let { return it } }
        if (!e.isOverText) return null
        val offset = e.offset
        return list.firstOrNull { it.keyRange.isValid && offset >= it.keyRange.startOffset && offset < it.keyRange.endOffset }
    }

    private fun show(target: Applied) {
        if (editor.isDisposed || !target.keyRange.isValid || target.keyRange !in targets().map { it.keyRange }) return
        hide()
        val key = target.spec.key
        val data = project.service<ProjectDataService>()
        val html = FlagHoverContent.html(key, data.flags.flag(key), data.experiments.forFlag(key), links(data, key))
        val content = FlagHoverPanel.create(html) { href ->
            FlagHoverPanel.handleLink(href)
            hide()
        }
        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .createPopup()
        content.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                mouseInPopup = true
                alarm.cancelAllRequests()
            }

            override fun mouseExited(e: MouseEvent) {
                mouseInPopup = false
                scheduleHide()
            }
        })
        popup = created
        shownFor = target
        created.show(RelativePoint(editor.contentComponent, below(target)))
    }

    /** Just under the hint (when it was hovered) or under the start of the key. */
    private fun below(target: Applied): Point {
        val inlay = target.inlay
        val bounds = inlay?.bounds
        if (bounds != null && inlay.isValid) return Point(bounds.x, bounds.y + bounds.height)
        val point = editor.visualPositionToXY(editor.offsetToVisualPosition(target.keyRange.startOffset))
        return Point(point.x, point.y + editor.lineHeight)
    }

    private fun links(data: ProjectDataService, key: String): HoverLinks? {
        val host = data.host ?: return null
        val projectId = data.selectedProject?.id ?: return null
        val base = "$host/project/$projectId"
        val flag = data.flags.flag(key)
        val experiment = data.experiments.forFlag(key)
        return HoverLinks(
            flagUrl = flag?.let { "$base/feature_flags/${it.id}" },
            experimentUrl = experiment?.let { "$base/experiments/${it.id}" },
            flagsUrl = "$base/feature_flags",
        )
    }

    private companion object {
        const val SHOW_DELAY_MS = 350
        const val HIDE_DELAY_MS = 300
    }
}
