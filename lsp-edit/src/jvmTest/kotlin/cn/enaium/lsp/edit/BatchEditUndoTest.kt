package cn.enaium.lsp.edit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Editor.applyEdits] batches range replacements into ONE undo step: a
 * completion's inserted text and its auto-import edits must revert together
 * with a single undo.
 */
class BatchEditUndoTest {

    private fun withEditor(text: String, body: (Editor) -> Unit) {
        val ctx = cn.enaium.imgui.ImGui.createContext()
        try {
            body(Editor(initialText = text, language = Language.kotlin))
        } finally {
            cn.enaium.imgui.ImGui.destroyContext(ctx)
        }
    }

    @Test
    fun oneUndoRevertsCompletionPlusAutoImport() {
        withEditor("val x = PI\n") { editor ->
            // Auto-import at the top plus the identifier replacement, in the
            // document's original coordinates (bottom-up application order).
            val applied = editor.applyEdits(
                listOf(
                    EditorEdit(DocPos(0, 8), DocPos(0, 10), "kotlin.math.PI"),
                    EditorEdit(DocPos(0, 0), DocPos(0, 0), "import kotlin.math.PI\n"),
                ),
            )
            assertTrue(applied)
            assertEquals("import kotlin.math.PI\nval x = kotlin.math.PI\n", editor.getText())

            editor.undo()

            assertEquals("val x = PI\n", editor.getText())
        }
    }

    @Test
    fun undoRestoresBothEditsThenRedoReapplies() {
        withEditor("val x = PI\n") { editor ->
            editor.applyEdits(
                listOf(
                    EditorEdit(DocPos(0, 8), DocPos(0, 10), "kotlin.math.PI"),
                    EditorEdit(DocPos(0, 0), DocPos(0, 0), "import kotlin.math.PI\n"),
                ),
            )
            editor.undo()
            assertEquals("val x = PI\n", editor.getText())

            editor.redo()

            assertEquals("import kotlin.math.PI\nval x = kotlin.math.PI\n", editor.getText())
        }
    }

    @Test
    fun separateApplyCallsStaySeparateUndoSteps() {
        withEditor("abc") { editor ->
            // Multi-char inserts: single-character typing merges into the
            // previous undo step (a typing convenience), which would defeat
            // this scenario.
            editor.applyEdits(listOf(EditorEdit(DocPos(0, 3), DocPos(0, 3), "XX")))
            editor.applyEdits(listOf(EditorEdit(DocPos(0, 5), DocPos(0, 5), "YY")))
            assertEquals("abcXXYY", editor.getText())

            editor.undo()
            assertEquals("abcXX", editor.getText())
            editor.undo()
            assertEquals("abc", editor.getText())
        }
    }
}
