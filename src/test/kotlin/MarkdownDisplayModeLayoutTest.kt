package uk.hpkns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.hpkns.mdcomments.MarkdownDisplayModeLayout

class MarkdownDisplayModeLayoutTest {
    @Test
    fun `standalone comment is display eligible`() {
        val text = "    // title\nnext()"
        val start = text.indexOf("//")
        val end = text.indexOf('\n')

        assertTrue(MarkdownDisplayModeLayout.isDisplayEligible(text, start, end))
    }

    @Test
    fun `inline trailing comment is not display eligible`() {
        val text = "val x = 1 // note\nnext()"
        val start = text.indexOf("//")
        val end = text.indexOf('\n')

        assertFalse(MarkdownDisplayModeLayout.isDisplayEligible(text, start, end))
    }

    @Test
    fun `collapse includes trailing newline for standalone comment`() {
        val text = "  // note   \nnext()"
        val start = text.indexOf("//")
        val end = text.indexOf('\n') - 3

        val collapseEnd = MarkdownDisplayModeLayout.collapseEndOffset(text, start, end)
        assertEquals(text.indexOf('\n') + 1, collapseEnd)
    }

    @Test
    fun `collapse starts at line indentation for standalone comment`() {
        val text = "  // note\nnext()"
        val start = text.indexOf("//")
        val end = text.indexOf('\n')

        val collapseStart = MarkdownDisplayModeLayout.collapseStartOffset(text, start, end)
        assertEquals(0, collapseStart)
    }

    @Test
    fun `collapse keeps end offset for inline trailing comment`() {
        val text = "val x = 1 // note\nnext()"
        val start = text.indexOf("//")
        val end = text.indexOf('\n')

        val collapseEnd = MarkdownDisplayModeLayout.collapseEndOffset(text, start, end)
        assertEquals(end, collapseEnd)
    }

    @Test
    fun `collapse start keeps inline trailing comment offset`() {
        val text = "val x = 1 // note\nnext()"
        val start = text.indexOf("//")
        val end = text.indexOf('\n')

        val collapseStart = MarkdownDisplayModeLayout.collapseStartOffset(text, start, end)
        assertEquals(start, collapseStart)
    }

    @Test
    fun `collapse includes crlf line separator for standalone comment`() {
        val text = "  // note\r\nnext()"
        val start = text.indexOf("//")
        val end = text.indexOf('\r')

        val collapseEnd = MarkdownDisplayModeLayout.collapseEndOffset(text, start, end)
        assertEquals(text.indexOf('\n') + 1, collapseEnd)
    }
}
