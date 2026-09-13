package cn.enaium.lsp.edit

import kotlin.test.Test
import kotlin.test.assertEquals

/** Paste must be ONE undo step regardless of text length. */
class UndoPasteTest {

    @Test
    fun pasteMultiLineUndoInOneStep() {
        val editor = Editor(initialText = "a\nb\nc\n", language = Language.kotlin)
        editor.setText("")
        editor.setCursor(DocPos(0, 0))
        // Simulate paste via replaceSelection (what editor.paste does).
        val pasted = "line1\nline2\nline3\nline4\nline5\n"
        editor.replaceSelection(pasted)
        assertEquals(pasted, editor.getText())
        editor.undo()
        assertEquals("", editor.getText(), "one undo must remove the whole paste")
        editor.redo()
        assertEquals(pasted, editor.getText(), "redo restores the whole paste")
    }

    @Test
    fun undoNotifiesWithInvertedOps() {
        val editor = Editor(initialText = "", language = Language.kotlin)
        val notifications = mutableListOf<List<EditOp>>()
        editor.onTextChange = { notifications.add(it) }
        editor.setCursor(DocPos(0, 0))
        editor.replaceSelection("hello")
        assertEquals(1, notifications.size)
        editor.undo()
        assertEquals(2, notifications.size)
        val undoOps = notifications[1]
        assertEquals(1, undoOps.size)
        // The host (LSP didChange) must see a DELETION, not a re-insertion.
        assertEquals(false, undoOps[0].insert)
        assertEquals("hello", undoOps[0].text)
        assertEquals("", editor.getText())
    }

    @Test
    fun typedCharsMergeIntoSingleUndo() {
        val editor = Editor(initialText = "", language = Language.kotlin)
        editor.setText("")
        editor.setCursor(DocPos(0, 0))
        for (c in "hello") {
            editor.replaceSelection(c.toString())
        }
        // All single-char insertions merge into one undo step.
        editor.undo()
        assertEquals("", editor.getText())
    }
}
