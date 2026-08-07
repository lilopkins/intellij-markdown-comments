package uk.hpkns.mdcomments

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import kotlin.math.max

class MarkdownCommentRenderer(
    markdown: String,
    private val indentLine: Int,
    private val indentColumns: Int,
) : EditorCustomElementRenderer {
    private val barWidth = 2
    private val barToTextPadding = 16
    private val lines = MarkdownLineParser.parse(markdown)

    /** Calculates inlay width from the widest rendered Markdown line. */
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val metrics = inlay.editor.contentComponent.getFontMetrics(baseFont(inlay.editor))
        val maxTextWidth =
            lines.maxOfOrNull { line ->
                line.segments.sumOf { segment ->
                    val font = fontFor(inlay.editor, line.kind, segment.style)
                    val fm = inlay.editor.contentComponent.getFontMetrics(font)
                    fm.stringWidth(segment.text)
                }
            } ?: 0
        return indentInPixels(inlay) + barToTextPadding + maxTextWidth + metrics.height / 2
    }

    /** Calculates inlay height based on rendered line count. */
    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val lineHeight = inlay.editor.lineHeight
        return max(lineHeight, lineHeight * lines.size + lineHeight / 4) + barToTextPadding
    }

    /** Paints simplified Markdown text using comment colors and style hints. */
    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val editor = inlay.editor
        val scheme = EditorColorsManager.getInstance().globalScheme
        val commentColor =
            scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT).foregroundColor
                ?: textAttributes.foregroundColor
                ?: g.color
        val lineHeight = editor.lineHeight
        var y = targetRegion.y + g.fontMetrics.ascent + (barToTextPadding / 2)
        val indentPixels = indentInPixels(inlay)
        val barX = targetRegion.x + indentPixels
        val textX = barX + barToTextPadding

        g.color = commentColor
        val barY = targetRegion.y
        val barHeight = (targetRegion.height - (barToTextPadding / 4)).coerceAtLeast(1)
        g.fillRect(barX, barY, barWidth, barHeight)

        for ((segments, kind) in lines) {
            var x = textX
            for ((text, style) in segments) {
                val font = fontFor(editor, kind, style)
                g.font = font
                g.drawString(text, x, y)
                val fm = editor.contentComponent.getFontMetrics(font)
                x += fm.stringWidth(text)
            }
            y += lineHeight
        }
    }

    /** Resolves the editor base font used for standard comment rendering. */
    private fun baseFont(editor: Editor): Font {
        val scheme = editor.colorsScheme
        return scheme.getFont(EditorFontType.PLAIN)
    }

    /** Chooses per-line font styling from parsed Markdown line type. */
    private fun fontFor(
        editor: Editor,
        kind: LineKind,
        style: MarkdownInlineStyler.Style,
    ): Font {
        val base = baseFont(editor)
        if (kind == LineKind.CODE || style == MarkdownInlineStyler.Style.CODE) {
            return Font(Font.MONOSPACED, Font.PLAIN, base.size)
        }

        var fontStyle = Font.PLAIN
        var fontSize = base.size

        if (kind == LineKind.HEADING) {
            fontStyle = fontStyle or Font.BOLD
            fontSize += 2
        }
        if (kind == LineKind.QUOTE) {
            fontStyle = 0 or Font.ITALIC
        }

        when (style) {
            MarkdownInlineStyler.Style.BOLD -> fontStyle = fontStyle or Font.BOLD
            MarkdownInlineStyler.Style.ITALIC -> fontStyle = fontStyle or Font.ITALIC
            MarkdownInlineStyler.Style.BOLD_ITALIC -> {
                fontStyle = fontStyle or Font.BOLD
                fontStyle = fontStyle or Font.ITALIC
            }
            MarkdownInlineStyler.Style.PLAIN,
            -> {}
        }

        return Font(Font.SANS_SERIF, fontStyle, fontSize)
    }

    /** Converts stored visual indentation columns into pixels for current editor metrics. */
    private fun indentInPixels(inlay: Inlay<*>): Int {
        if (indentColumns <= 0) return 0
        val editor = inlay.editor
        val maxLine = (editor.document.lineCount - 1).coerceAtLeast(0)
        val safeLine = indentLine.coerceIn(0, maxLine)
        val start = editor.visualPositionToXY(VisualPosition(safeLine, 0))
        val end = editor.visualPositionToXY(VisualPosition(safeLine, indentColumns))
        return (end.x - start.x).coerceAtLeast(0)
    }

    private enum class LineKind {
        NORMAL,
        HEADING,
        CODE,
        QUOTE,
    }

    private data class Line(
        val segments: List<MarkdownInlineStyler.Segment>,
        val kind: LineKind,
    )

    private object MarkdownLineParser {
        private val heading = Regex("""^\s{0,3}#{1,6}\s+(.+)$""")
        private val listItem = Regex("""^\s*([-*+]|\d+\.)\s+(.+)$""")
        private val link = Regex("""\[(.+?)]\((.+?)\)""")

        /** Parses Markdown into paint-ready lines with lightweight style classification. */
        fun parse(markdown: String): List<Line> {
            if (markdown.isBlank()) return listOf(Line(MarkdownInlineStyler.parse(""), LineKind.NORMAL))

            val result = ArrayList<Line>()
            var inCodeFence = false

            for (rawLine in markdown.lines()) {
                val trimmed = rawLine.trimEnd()
                if (trimmed.startsWith("```")) {
                    inCodeFence = !inCodeFence
                    continue
                }

                if (inCodeFence) {
                    result += Line(listOf(MarkdownInlineStyler.Segment(trimmed, MarkdownInlineStyler.Style.CODE)), LineKind.CODE)
                    continue
                }

                val headingMatch = heading.find(trimmed)
                if (headingMatch != null) {
                    result += Line(parseInline(headingMatch.groupValues[1]), LineKind.HEADING)
                    continue
                }

                if (trimmed.startsWith(">")) {
                    result += Line(parseInline(trimmed.removePrefix(">").trimStart()), LineKind.QUOTE)
                    continue
                }

                val listMatch = listItem.find(trimmed)
                if (listMatch != null) {
                    result += Line(parseInline("\u2022 ${listMatch.groupValues[2]}"), LineKind.NORMAL)
                    continue
                }

                result += Line(parseInline(trimmed), LineKind.NORMAL)
            }

            return if (result.isEmpty()) listOf(Line(MarkdownInlineStyler.parse(""), LineKind.NORMAL)) else result
        }

        private fun parseInline(line: String): List<MarkdownInlineStyler.Segment> {
            val withLinks =
                link.replace(line) { match ->
                    "${match.groupValues[1]} <${match.groupValues[2]}>"
                }
            return MarkdownInlineStyler.parse(withLinks)
        }
    }
}
