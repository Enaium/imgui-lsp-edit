package cn.enaium.lsp.edit.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffTest {

    @Test
    fun identicalTextsAreAllContext() {
        val d = Diff.compute("a\nb\nc", "a\nb\nc")
        assertEquals(listOf(DiffKind.CONTEXT, DiffKind.CONTEXT, DiffKind.CONTEXT), d.map { it.kind })
        assertEquals(listOf(1, 2, 3), d.map { it.oldLine })
        assertEquals(listOf(1, 2, 3), d.map { it.newLine })
    }

    @Test
    fun emptyTexts() {
        assertTrue(Diff.compute("", "").isEmpty())
    }

    @Test
    fun pureInsertion() {
        val d = Diff.compute("a", "a\nb")
        assertEquals(listOf(DiffKind.CONTEXT, DiffKind.ADDED), d.map { it.kind })
        assertEquals(null, d[1].oldLine)
        assertEquals(2, d[1].newLine)
        assertEquals("b", d[1].text)
    }

    @Test
    fun pureDeletion() {
        val d = Diff.compute("a\nb", "a")
        assertEquals(listOf(DiffKind.CONTEXT, DiffKind.REMOVED), d.map { it.kind })
        assertEquals(2, d[1].oldLine)
        assertEquals(null, d[1].newLine)
    }

    @Test
    fun middleChange() {
        val d = Diff.compute("x\na\ny", "x\nb\ny")
        // A replace is one removed + one added line; the Myers edit script
        // may emit them in either order.
        val kinds = d.map { it.kind }
        assertEquals(
            listOf(DiffKind.CONTEXT, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.CONTEXT)
                .toSet(),
            kinds.toSet(),
        )
        assertEquals("a", d.first { it.kind == DiffKind.REMOVED }.text)
        assertEquals("b", d.first { it.kind == DiffKind.ADDED }.text)
        assertEquals(2, d.first { it.kind == DiffKind.REMOVED }.oldLine)
        assertEquals(2, d.first { it.kind == DiffKind.ADDED }.newLine)
    }

    @Test
    fun lineNumbersAreStable() {
        // A replace in the middle keeps numbering straight regardless of the
        // order the edit script emits the removed/added lines.
        val d = Diff.compute("1\n2\n3\n4\n5", "1\n2\nX\n5")
        val added = d.first { it.kind == DiffKind.ADDED }
        assertEquals(3, added.newLine)
        val removed = d.filter { it.kind == DiffKind.REMOVED }
        assertEquals(setOf(3, 4), removed.map { it.oldLine }.toSet())
    }

    @Test
    fun trailingNewlineDoesNotProduceExtraLine() {
        assertEquals(1, Diff.compute("a\n", "a\n").size)
        assertEquals(2, Diff.compute("a\n", "a\nb\n").size)
    }
}
