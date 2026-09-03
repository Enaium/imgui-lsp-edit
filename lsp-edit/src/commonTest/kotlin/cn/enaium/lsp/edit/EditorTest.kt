package cn.enaium.lsp.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextBufferTest {

    @Test
    fun setTextSplitsLines() {
        val buffer = TextBuffer("a\nb\nc")
        assertEquals(3, buffer.lineCount())
        assertEquals("a", buffer.line(0))
        assertEquals("b", buffer.line(1))
        assertEquals("c", buffer.line(2))
        assertEquals("a\nb\nc", buffer.getText())
    }

    @Test
    fun emptyTextHasOneLine() {
        val buffer = TextBuffer("")
        assertEquals(1, buffer.lineCount())
        assertEquals("", buffer.line(0))
    }

    @Test
    fun insertSingleLine() {
        val buffer = TextBuffer("hello")
        buffer.insert(DocPos(0, 2), "XX")
        assertEquals("heXXllo", buffer.getText())
    }

    @Test
    fun insertMultiLineSplits() {
        val buffer = TextBuffer("ab\ncd")
        buffer.insert(DocPos(0, 1), "\n")
        assertEquals("a\nb\ncd", buffer.getText())
        assertEquals(3, buffer.lineCount())
    }

    @Test
    fun eraseWithinLine() {
        val buffer = TextBuffer("hello world")
        buffer.erase(DocPos(0, 2), DocPos(0, 5))
        assertEquals("he world", buffer.getText())
    }

    @Test
    fun eraseAcrossLinesMerges() {
        val buffer = TextBuffer("ab\ncd\nef")
        buffer.erase(DocPos(0, 1), DocPos(2, 1))
        assertEquals("af", buffer.getText())
        assertEquals(1, buffer.lineCount())
    }

    @Test
    fun clampBounds() {
        val buffer = TextBuffer("ab\ncd")
        assertEquals(DocPos(1, 2), buffer.clamp(DocPos(5, 5)))
        assertEquals(DocPos(0, 0), buffer.clamp(DocPos(-1, -1)))
        assertEquals(DocPos(0, 2), buffer.clamp(DocPos(0, 99)))
    }

    @Test
    fun offsetCrossesLines() {
        val buffer = TextBuffer("ab\ncd")
        assertEquals(DocPos(1, 0), buffer.offset(DocPos(0, 2), 1))
        assertEquals(DocPos(0, 2), buffer.offset(DocPos(1, 0), -1))
        assertEquals(DocPos(1, 2), buffer.offset(DocPos(0, 2), 4))
        assertEquals(DocPos(0, 0), buffer.offset(DocPos(0, 0), -5))
        assertEquals(DocPos(1, 2), buffer.offset(DocPos(1, 2), 10))
    }

    @Test
    fun sectionText() {
        val buffer = TextBuffer("ab\ncd\nef")
        assertEquals("b\ncd\ne", buffer.getSectionText(DocPos(0, 1), DocPos(2, 1)))
        assertEquals("", buffer.getSectionText(DocPos(1, 1), DocPos(1, 1)))
    }

    @Test
    fun charCount() {
        val buffer = TextBuffer("ab\ncd")
        assertEquals(5, buffer.charCount()) // 4 chars + 1 newline
    }
}

class EditorStateTest {

    private fun editor(text: String) = Editor(text, language = Language.cpp)

    @Test
    fun insertMovesCursor() {
        val e = editor("hi")
        e.setCursor(DocPos(0, 1))
        e.inputText("!")
        assertEquals("h!i", e.getText())
        assertEquals(DocPos(0, 2), e.cursor)
    }

    @Test
    fun undoRedo() {
        val e = editor("hi")
        e.setCursor(DocPos(0, 2))
        e.inputText("!")
        assertEquals("hi!", e.getText())
        assertTrue(e.canUndo())
        e.undo()
        assertEquals("hi", e.getText())
        assertTrue(e.canRedo())
        e.redo()
        assertEquals("hi!", e.getText())
    }

    @Test
    fun selectionReplace() {
        val e = editor("hello world")
        e.setCursor(DocPos(0, 0))
        e.setCursorWithAnchor(DocPos(0, 5))
        assertEquals("hello", e.getSelectedText())
        e.replaceSelection("bye")
        assertEquals("bye world", e.getText())
        e.undo()
        assertEquals("hello world", e.getText())
    }

    @Test
    fun multiLineInsert() {
        val e = editor("a\nb")
        e.setCursor(DocPos(0, 1))
        e.inputText("\n")
        assertEquals("a\n\nb", e.getText())
        assertEquals(3, e.lineCount())
    }

    @Test
    fun backspaceAtLineStartJoinsLines() {
        val e = editor("ab\ncd")
        e.setCursor(DocPos(1, 0))
        // deleteLeft via deleteRight? Simulate by direct erase.
        e.eraseRange(DocPos(0, 2), DocPos(1, 0))
        assertEquals("abcd", e.getText())
    }

    @Test
    fun findWraps() {
        val e = editor("foo bar foo")
        e.setCursor(DocPos(0, 4))
        e.setFindString("foo")
        assertTrue(e.selectNextOccurrence())
        // Second occurrence after cursor; cursor lands at its end.
        val (s, en) = e.selectionBounds()!!
        assertEquals(DocPos(0, 8), s)
        assertEquals(DocPos(0, 11), en)
    }

    @Test
    fun caretLayoutRequiresRender() {
        // Defaults before any render exist and do not crash.
        val e = editor("x")
        assertTrue(e.lineHeightPx() >= 1f)
        assertTrue(e.charWidthPx() >= 1f)
    }

    @Test
    fun tokenProviderOverridesBuiltin() {
        val e = editor("fun main()")
        e.language = Language.kotlin
        var called = false
        e.tokenProvider = { line ->
            called = true
            assertEquals(0, line)
            listOf(TokenSpan(0, 3, PaletteIndex.KEYWORD))
        }
        val spans = e.spansOf(0)
        assertTrue(called)
        assertEquals(1, spans.size)
        assertEquals(PaletteIndex.KEYWORD, spans[0].palette)
    }
}

class SyntaxHighlighterTest {

    @Test
    fun keywordsAreHighlighted() {
        val result = SyntaxHighlighter.tokenize(Language.kotlin, "fun main()", TokenState.NONE)
        val keyword = result.spans.first { it.palette == PaletteIndex.KEYWORD }
        assertEquals(0, keyword.start)
        assertEquals(3, keyword.end)
    }

    @Test
    fun lineCommentSwallowsRest() {
        val result = SyntaxHighlighter.tokenize(Language.cpp, "int x; // note", TokenState.NONE)
        val comment = result.spans.first { it.palette == PaletteIndex.COMMENT }
        assertEquals(7, comment.start)
        assertEquals("// note".length + 7, comment.end)
        assertEquals(TokenState.NONE, result.carryState)
    }

    @Test
    fun blockCommentCarriesAcrossLines() {
        val first = SyntaxHighlighter.tokenize(Language.cpp, "/* open", TokenState.NONE)
        assertTrue(first.carryState and TokenState.BLOCK_COMMENT != 0)
        val second = SyntaxHighlighter.tokenize(Language.cpp, "middle", first.carryState)
        assertTrue(second.carryState and TokenState.BLOCK_COMMENT != 0)
        assertEquals(1, second.spans.size)
        val third = SyntaxHighlighter.tokenize(Language.cpp, "close */ x", second.carryState)
        assertEquals(TokenState.NONE, third.carryState)
    }

    @Test
    fun stringWithEscapes() {
        // "\"a\\\"b\"" is the 8-char source `"a\"b"` (quote, a, backslash, quote, b, quote = 6 chars)
        val result = SyntaxHighlighter.tokenize(Language.json, "\"a\\\"b\"", TokenState.NONE)
        val string = result.spans.first { it.palette == PaletteIndex.STRING }
        assertEquals(0, string.start)
        assertEquals(6, string.end)
    }

    @Test
    fun numbersHighlighted() {
        val result = SyntaxHighlighter.tokenize(Language.cpp, "x = 42;", TokenState.NONE)
        val number = result.spans.first { it.palette == PaletteIndex.NUMBER }
        assertEquals(4, number.start)
        assertEquals(6, number.end)
    }
}
/** Folding state machine tests (pure state; no render loop needed). */
class FoldingTest {

    private fun editor(): Editor = Editor("a\nb\nc\nd\ne\nf\ng", language = Language.cpp)

    @Test
    fun foldHidesLinesAndRestores() {
        val e = editor()
        e.setFoldRanges(listOf(EditorFoldRange(1, 3)))
        assertTrue(e.isLineVisible(0))
        assertTrue(e.isLineVisible(1))
        assertTrue(e.isLineVisible(4))

        e.toggleFold(1)
        assertTrue(e.isLineFolded(1))
        assertTrue(e.isLineVisible(0))
        assertTrue(e.isLineVisible(1)) // fold start stays visible
        assertFalse(e.isLineVisible(2))
        assertFalse(e.isLineVisible(3))

        e.toggleFold(1)
        assertFalse(e.isLineFolded(1))
        assertTrue(e.isLineVisible(2))
        assertTrue(e.isLineVisible(3))
    }

    @Test
    fun toggleFoldInsideRangeFoldsInnermost() {
        val e = editor()
        e.setFoldRanges(
            listOf(
                EditorFoldRange(0, 6),
                EditorFoldRange(2, 4),
            ),
        )
        // Toggling from inside the inner range folds the inner range.
        // A folded [start, end] hides start+1..end inclusive (VS Code-style).
        e.toggleFold(3)
        assertTrue(e.isLineFolded(2))
        assertFalse(e.isLineVisible(3))
        assertFalse(e.isLineVisible(4))
        assertTrue(e.isLineVisible(5))
    }

    @Test
    fun unfoldAroundExpandsEnclosingFolds() {
        val e = editor()
        e.setFoldRanges(
            listOf(
                EditorFoldRange(0, 6),
                EditorFoldRange(2, 4),
            ),
        )
        e.toggleFold(0)
        e.toggleFold(2)
        // Everything below line 0 is hidden; unfold around 3 expands both.
        e.unfoldAround(3)
        assertFalse(e.isLineFolded(0))
        assertFalse(e.isLineFolded(2))
        assertTrue(e.isLineVisible(3))
    }

    @Test
    fun setFoldRangesPrunesStaleCollapse() {
        val e = editor()
        e.setFoldRanges(listOf(EditorFoldRange(1, 3)))
        e.toggleFold(1)
        assertTrue(e.isLineFolded(1))

        e.setFoldRanges(listOf(EditorFoldRange(1, 3), EditorFoldRange(5, 6)))
        assertTrue(e.isLineFolded(1)) // still exists -> kept

        e.setFoldRanges(listOf(EditorFoldRange(5, 6)))
        assertFalse(e.isLineFolded(1)) // gone -> pruned
        assertFalse(e.isLineFolded(5))
    }

    @Test
    fun setCursorIntoFoldedRegionUnfolds() {
        val e = editor()
        e.setFoldRanges(listOf(EditorFoldRange(1, 3)))
        e.toggleFold(1) // hide 2..3
        assertFalse(e.isLineVisible(3))

        // Moving the cursor into a hidden line expands the enclosing fold.
        e.setCursor(DocPos(3, 0))
        assertFalse(e.isLineFolded(1))
        assertTrue(e.isLineVisible(3))
    }
}
