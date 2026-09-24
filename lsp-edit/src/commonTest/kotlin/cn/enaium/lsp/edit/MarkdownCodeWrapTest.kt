package cn.enaium.lsp.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Code-block wrapping. The failure modes are nasty for a UI: a wrap loop that
 * does not advance hangs the frame, and a wrong break silently drops text from
 * documentation. Widths are driven by an injected per-character metric so the
 * cases are exact without a font or an ImGui context.
 */
class MarkdownCodeWrapTest {

    private fun wrap(line: String, width: Float): List<MarkdownCode.CodeRow> {
        val out = ArrayList<MarkdownCode.CodeRow>()
        MarkdownCode.wrapLine(out, 0, line, width) { 1f }
        return out
    }

    /** Asserts the wrapped rows and the invariants every caller relies on. */
    private fun assertWrap(line: String, width: Float, expected: String) {
        val rows = wrap(line, width)
        for ((i, row) in rows.withIndex()) {
            assertTrue(row.from <= row.to, "row $i has an inverted range")
            val span = (row.to - row.from).toFloat()
            assertTrue(
                span <= width || row.to - row.from == 1,
                "row $i ('${line.substring(row.from, row.to)}') is $span wide, over $width",
            )
            if (i > 0) {
                assertTrue(
                    row.from >= rows[i - 1].to,
                    "row $i overlaps its predecessor",
                )
                assertTrue(
                    row.from >= line.length || line[row.from] != ' ',
                    "row $i starts with a consumed separator",
                )
            }
        }
        assertEquals(expected, rows.joinToString("|") { line.substring(it.from, it.to) })
    }

    @Test
    fun wrapsAtSpaces() = assertWrap("hello world again", 6f, "hello|world|again")

    @Test
    fun keepsIndentationOutOfTheBreakPoint() =
        assertWrap("  abc def", 5f, "  abc|def")

    @Test
    fun hardSplitsAWordTooLongForARow() = assertWrap("abcdefghij", 3f, "abc|def|ghi|j")

    @Test
    fun terminatesWhenARowIsNarrowerThanOneGlyph() =
        assertWrap("abc", 0.5f, "a|b|c")

    @Test
    fun emptyLineKeepsOneEmptyRow() {
        val rows = wrap("", 10f)
        assertEquals(1, rows.size)
        assertEquals(0, rows[0].from)
        assertEquals(0, rows[0].to)
    }

    @Test
    fun lineThatFitsIsOneRow() = assertWrap("val x = 1", 40f, "val x = 1")
}
