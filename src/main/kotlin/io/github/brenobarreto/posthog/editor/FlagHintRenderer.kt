package io.github.brenobarreto.posthog.editor

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

/** Draws a flag's status in italics after the end of a line, like VS Code's "after" decoration. */
class FlagHintRenderer(val presentation: Presentation) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = metrics(inlay.editor)
        return margin(inlay.editor) + metrics.stringWidth(presentation.text)
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val metrics = metrics(editor)
        g.font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
        g.color = color(editor, presentation.color)
        val baseline = targetRegion.y + (targetRegion.height - metrics.height) / 2 + metrics.ascent
        g.drawString(presentation.text, targetRegion.x + margin(editor), baseline)
    }

    private fun metrics(editor: Editor) =
        editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.ITALIC))

    /** 1.5em, as in the VS Code extension. */
    private fun margin(editor: Editor) = metrics(editor).charWidth('m') * 3 / 2

    companion object {
        // VS Code's colors on dark themes, darker variants on light ones so they stay readable.
        private val ENABLED = JBColor(Color(0x2E8B0F), Color(0x4CBB17))
        private val WARNING = JBColor(Color(0xB07C00), Color(0xF9BD2B))
        private val EXPERIMENT = JBColor(Color(0x1D4AFF), Color(0x6B8CFF))

        fun color(editor: Editor, color: HintColor): Color = when (color) {
            HintColor.ENABLED -> ENABLED
            HintColor.WARNING -> WARNING
            HintColor.EXPERIMENT -> EXPERIMENT
            HintColor.GHOST ->
                editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLINE_PARAMETER_HINT)?.foregroundColor ?: JBColor.GRAY
        }
    }
}
