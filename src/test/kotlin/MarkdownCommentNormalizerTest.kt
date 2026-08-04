package uk.hpkns

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.hpkns.mdcomments.MarkdownCommentNormalizer

class MarkdownCommentNormalizerTest {
    /** Verifies prefix stripping for grouped single-line comments. */
    @Test
    fun `normalizes slash slash comments`() {
        val input =
            """
            // # Title
            // - one
            // - two
            """.trimIndent()

        assertEquals("# Title\n- one\n- two", MarkdownCommentNormalizer.normalize(input))
    }

    /** Verifies delimiter and leading-asterisk stripping for block comments. */
    @Test
    fun `normalizes block comments with stars`() {
        val input =
            """
            /*
             * **bold**
             * `code`
             */
            """.trimIndent()

        assertEquals("**bold**\n`code`", MarkdownCommentNormalizer.normalize(input))
    }
}
