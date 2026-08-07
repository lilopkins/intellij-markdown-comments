package uk.hpkns.mdcomments

internal object MarkdownInlineStyler {
    private val inlineCode = Regex("""`([^`]+)`""")

    internal enum class Style {
        PLAIN,
        BOLD,
        ITALIC,
        BOLD_ITALIC,
        CODE,
    }

    internal data class Segment(
        val text: String,
        val style: Style,
    )

    /** Parses inline Markdown emphasis into style segments while preserving text. */
    internal fun parse(line: String): List<Segment> {
        if (line.isEmpty()) return listOf(Segment("", Style.PLAIN))

        val segments = mutableListOf<Segment>()
        var index = 0
        for (match in inlineCode.findAll(line)) {
            appendParsedText(segments, line.substring(index, match.range.first), Style.PLAIN)
            segments += Segment(match.groupValues[1], Style.CODE)
            index = match.range.last + 1
        }
        appendParsedText(segments, line.substring(index), Style.PLAIN)
        return mergeAdjacent(segments)
    }

    private fun appendParsedText(
        segments: MutableList<Segment>,
        text: String,
        style: Style,
    ) {
        if (text.isNotEmpty()) {
            segments += parseEmphasis(text, style)
        }
    }

    private fun parseEmphasis(
        source: String,
        baseStyle: Style,
    ): List<Segment> {
        if (source.isEmpty()) return emptyList()

        val emphasis = findFirstEmphasis(source) ?: return listOf(Segment(source, baseStyle))

        val result = mutableListOf<Segment>()
        val before = source.substring(0, emphasis.openAt)
        if (before.isNotEmpty()) {
            result += Segment(before, baseStyle)
        }

        val inside = source.substring(emphasis.openAt + emphasis.marker.length, emphasis.closeAt)
        result += parseEmphasis(inside, applyStyle(baseStyle, emphasis.marker))

        val after = source.substring(emphasis.closeAt + emphasis.marker.length)
        if (after.isNotEmpty()) {
            result += parseEmphasis(after, baseStyle)
        }
        return result
    }

    private fun findFirstEmphasis(source: String): EmphasisMatch? {
        return listOf("**", "__", "*", "_")
            .mapNotNull { marker ->
                val openAt = source.indexOf(marker)
                if (openAt < 0) return@mapNotNull null
                val closeAt = source.indexOf(marker, openAt + marker.length)
                if (closeAt < 0) return@mapNotNull null
                EmphasisMatch(openAt, closeAt, marker)
            }.minByOrNull { it.openAt }
    }

    private fun applyStyle(
        current: Style,
        marker: String,
    ): Style {
        val boldMarker = marker.length == 2
        val italicMarker = marker.length == 1
        val bold = current == Style.BOLD || current == Style.BOLD_ITALIC || boldMarker
        val italic = current == Style.ITALIC || current == Style.BOLD_ITALIC || italicMarker
        return when {
            bold && italic -> Style.BOLD_ITALIC
            bold -> Style.BOLD
            italic -> Style.ITALIC
            else -> Style.PLAIN
        }
    }

    private fun mergeAdjacent(segments: List<Segment>): List<Segment> {
        if (segments.isEmpty()) return listOf(Segment("", Style.PLAIN))
        val merged = mutableListOf<Segment>()
        for (segment in segments) {
            if (segment.text.isEmpty()) continue
            val previous = merged.lastOrNull()
            if (previous != null && previous.style == segment.style) {
                merged[merged.lastIndex] = previous.copy(text = previous.text + segment.text)
            } else {
                merged += segment
            }
        }
        return if (merged.isEmpty()) listOf(Segment("", Style.PLAIN)) else merged
    }

    private data class EmphasisMatch(
        val openAt: Int,
        val closeAt: Int,
        val marker: String,
    )
}
