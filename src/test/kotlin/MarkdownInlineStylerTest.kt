package uk.hpkns

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.hpkns.mdcomments.MarkdownInlineStyler

class MarkdownInlineStylerTest {
    @Test
    fun `parses bold and italic segments`() {
        val parsed = MarkdownInlineStyler.parse("before **bold** and *italic* after")

        assertEquals(
            listOf(
                MarkdownInlineStyler.Segment("before ", MarkdownInlineStyler.Style.PLAIN),
                MarkdownInlineStyler.Segment("bold", MarkdownInlineStyler.Style.BOLD),
                MarkdownInlineStyler.Segment(" and ", MarkdownInlineStyler.Style.PLAIN),
                MarkdownInlineStyler.Segment("italic", MarkdownInlineStyler.Style.ITALIC),
                MarkdownInlineStyler.Segment(" after", MarkdownInlineStyler.Style.PLAIN),
            ),
            parsed,
        )
    }

    @Test
    fun `keeps inline code as code segment`() {
        val parsed = MarkdownInlineStyler.parse("use `value` now")

        assertEquals(
            listOf(
                MarkdownInlineStyler.Segment("use ", MarkdownInlineStyler.Style.PLAIN),
                MarkdownInlineStyler.Segment("value", MarkdownInlineStyler.Style.CODE),
                MarkdownInlineStyler.Segment(" now", MarkdownInlineStyler.Style.PLAIN),
            ),
            parsed,
        )
    }
}
