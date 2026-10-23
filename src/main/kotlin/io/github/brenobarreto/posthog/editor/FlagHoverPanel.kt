package io.github.brenobarreto.posthog.editor

import com.intellij.codeInsight.hint.HintUtil
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.net.URLDecoder
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/** The Swing component for [FlagHoverContent]'s HTML, and what its links do. */
internal object FlagHoverPanel {
    private const val WIDTH = 380

    fun create(html: String, onLink: (String) -> Unit): JPanel {
        val kit = HTMLEditorKitBuilder.simple()
        val font = UIUtil.getLabelFont()
        kit.styleSheet.addRule(
            "body, p { font-family: '${font.family}'; font-size: ${font.size}pt; color: ${hex(UIUtil.getLabelForeground())}; margin: 2px 0; } " +
                "a { color: ${hex(JBUI.CurrentTheme.Link.Foreground.ENABLED)}; } " +
                "code, pre { font-family: Monospaced; font-size: ${font.size}pt; }",
        )
        val pane = JEditorPane().apply {
            editorKit = kit
            isEditable = false
            isOpaque = false
            text = html
            addHyperlinkListener { event ->
                if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) onLink(event.description.orEmpty())
            }
        }
        val width = JBUI.scale(WIDTH)
        pane.setSize(width, Short.MAX_VALUE.toInt())
        pane.preferredSize = Dimension(width, pane.preferredSize.height)
        return JPanel(BorderLayout()).apply {
            background = HintUtil.getInformationColor()
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(6, 10))
            add(pane, BorderLayout.CENTER)
        }
    }

    /**
     * Acts on a clicked link: copies the key for `posthog-copy:`, opens `http(s)` URLs in the browser,
     * and ignores every other scheme. Returns whether the link did something.
     */
    fun handleLink(
        href: String,
        browse: (String) -> Unit = BrowserUtil::browse,
        copy: (String) -> Unit = { CopyPasteManager.getInstance().setContents(StringSelection(it)) },
    ): Boolean = when {
        href.startsWith(FlagHoverContent.COPY_SCHEME) -> {
            copy(URLDecoder.decode(href.removePrefix(FlagHoverContent.COPY_SCHEME), Charsets.UTF_8))
            true
        }
        href.startsWith("https://") || href.startsWith("http://") -> {
            browse(href)
            true
        }
        else -> false
    }

    private fun hex(color: Color) = "#%02x%02x%02x".format(color.red, color.green, color.blue)
}
