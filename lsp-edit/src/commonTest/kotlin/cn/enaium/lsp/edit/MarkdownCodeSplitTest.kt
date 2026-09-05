package cn.enaium.lsp.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for the fenced-code-block splitting used by [MarkdownCode]. */
class MarkdownCodeSplitTest {

    @Test
    fun plainMarkdownHasSingleProseSegment() {
        val segments = MarkdownCode.splitFencedBlocks("**bold** and `inline`")
        assertEquals(1, segments.size)
        assertTrue(segments[0] is MarkdownCode.Prose)
    }

    @Test
    fun fencedBlockIsExtractedWithInfoString() {
        val segments = MarkdownCode.splitFencedBlocks(
            """
            Before.
            ```kotlin
            fun main() {}
            ```
            After.
            """.trimIndent(),
        )
        assertEquals(3, segments.size)
        assertEquals("Before.", (segments[0] as MarkdownCode.Prose).text.trim())
        val block = segments[1] as MarkdownCode.FencedBlock
        assertEquals("kotlin", block.info)
        assertEquals("fun main() {}", block.code.trim())
        assertEquals("After.", (segments[2] as MarkdownCode.Prose).text.trim())
    }

    @Test
    fun unclosedFenceRunsToEnd() {
        val segments = MarkdownCode.splitFencedBlocks(
            """
            Text
            ```python
            x = 1
            """.trimIndent(),
        )
        assertEquals(2, segments.size)
        val block = segments[1] as MarkdownCode.FencedBlock
        assertEquals("python", block.info)
        assertEquals("x = 1", block.code.trim())
    }

    @Test
    fun multipleBlocksSplitInOrder() {
        val segments = MarkdownCode.splitFencedBlocks(
            """
            ```a
            1
            ```
            mid
            ```b
            2
            ```
            end
            """.trimIndent(),
        )
        assertEquals(4, segments.size)
        assertEquals("a", (segments[0] as MarkdownCode.FencedBlock).info)
        assertEquals("mid", (segments[1] as MarkdownCode.Prose).text.trim())
        assertEquals("b", (segments[2] as MarkdownCode.FencedBlock).info)
        assertEquals("end", (segments[3] as MarkdownCode.Prose).text.trim())
    }

    @Test
    fun fenceMarkerInsideCodeDoesNotClose() {
        // A ``` that is not at the start of a line (e.g. inside a string)
        // does not close the fence; the block runs to the end of input.
        val segments = MarkdownCode.splitFencedBlocks(
            """
            ```kotlin
            val s = "```"
            """.trimIndent(),
        )
        assertEquals(1, segments.size)
        val block = segments[0] as MarkdownCode.FencedBlock
        assertEquals("kotlin", block.info)
        assertTrue(block.code.contains("val s = \"```\""))
    }
}
