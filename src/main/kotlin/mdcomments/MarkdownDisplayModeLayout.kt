package uk.hpkns.mdcomments

internal object MarkdownDisplayModeLayout {
    internal fun isDisplayEligible(
        documentText: CharSequence,
        startOffset: Int,
        endOffset: Int,
    ): Boolean {
        if (startOffset >= endOffset) return false
        if (startOffset !in 0..documentText.length) return false
        if (endOffset !in 0..documentText.length) return false

        val lineStart = lineStartOffset(documentText, startOffset)
        if (containsNonWhitespace(documentText, lineStart, startOffset)) return false

        val lineEnd = lineEndOffset(documentText, endOffset)
        if (containsNonWhitespace(documentText, endOffset, lineEnd)) return false

        return true
    }

    internal fun collapseEndOffset(
        documentText: CharSequence,
        startOffset: Int,
        endOffset: Int,
    ): Int {
        if (!isDisplayEligible(documentText, startOffset, endOffset)) return endOffset

        val lineEnd = lineEndOffset(documentText, endOffset)
        val afterLineEnd = includeLineSeparator(documentText, lineEnd)
        return afterLineEnd.coerceIn(endOffset, documentText.length)
    }

    private fun lineStartOffset(
        text: CharSequence,
        offset: Int,
    ): Int {
        var index = offset.coerceIn(0, text.length)
        while (index > 0) {
            val previous = text[index - 1]
            if (previous == '\n' || previous == '\r') break
            index--
        }
        return index
    }

    private fun lineEndOffset(
        text: CharSequence,
        offset: Int,
    ): Int {
        var index = offset.coerceIn(0, text.length)
        while (index < text.length) {
            val char = text[index]
            if (char == '\n' || char == '\r') break
            index++
        }
        return index
    }

    private fun includeLineSeparator(
        text: CharSequence,
        offset: Int,
    ): Int {
        if (offset >= text.length) return text.length
        return when (text[offset]) {
            '\r' -> if (offset + 1 < text.length && text[offset + 1] == '\n') offset + 2 else offset + 1
            '\n' -> offset + 1
            else -> offset
        }
    }

    private fun containsNonWhitespace(
        text: CharSequence,
        start: Int,
        end: Int,
    ): Boolean {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(0, text.length)
        if (safeStart >= safeEnd) return false
        for (index in safeStart until safeEnd) {
            if (!text[index].isWhitespace()) return true
        }
        return false
    }
}
