package uk.hpkns.mdcomments

object MarkdownCommentNormalizer {
    private val singleLinePrefix = Regex("""^\s*(//+|#+|--+|;+)\s?""")
    private val leadingStar = Regex("""^\s*\*+\s?""")

    /** Converts raw PSI comment text into markdown-ready content. */
    fun normalize(commentText: String): String {
        if (commentText.isBlank()) return ""

        val lines = commentText.replace("\r\n", "\n").replace('\r', '\n').lines()
        val stripped =
            when {
                commentText.trimStart().startsWith("/*") -> normalizeBlock(lines)
                else -> lines.map(::normalizeLine)
            }

        return stripped
            .dropWhile(String::isBlank)
            .dropLastWhile(String::isBlank)
            .joinToString("\n")
    }

    /** Strips block comment delimiters and common leading asterisk prefixes. */
    private fun normalizeBlock(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        return lines.mapIndexed { index, line ->
            var text = line
            if (index == 0) {
                text = text.substringAfter("/*", "")
            }
            if (index == lines.lastIndex) {
                text = text.substringBeforeLast("*/", text)
            }
            normalizeLine(leadingStar.replace(text, ""))
        }
    }

    /** Removes single-line comment prefixes while preserving Markdown structure. */
    private fun normalizeLine(line: String): String = singleLinePrefix.replace(line, "")
}
