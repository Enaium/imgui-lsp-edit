package cn.enaium.lsp.edit

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImVec2
import kotlin.test.Test
import kotlin.test.assertEquals

/** Shift+click selection and drag auto-scroll smoke tests. */
class ShiftSelectTest {

    private fun withEditor(text: String, block: (Editor) -> Unit) {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(800f, 600f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)
            val editor = Editor(initialText = text, language = Language.kotlin)
            block(editor)
        } finally {
            ImGui.destroyContext(ctx)
        }
    }

    @Test
    fun shiftArrowKeepsAnchor() {
        withEditor("hello world") { editor ->
            editor.setCursor(DocPos(0, 0))
            // Simulate Shift+Right via the public API path: move with anchor.
            editor.setCursorWithAnchor(DocPos(0, 5))
            assertEquals(DocPos(0, 0), editor.selectionAnchor)
            assertEquals(DocPos(0, 5), editor.cursor)
            val sel = editor.selectionBounds()
            assertEquals(DocPos(0, 0) to DocPos(0, 5), sel)
        }
    }

    @Test
    fun plainClickResetsSelection() {
        withEditor("hello world") { editor ->
            editor.setCursor(DocPos(0, 0))
            editor.setCursorWithAnchor(DocPos(0, 5))
            editor.setCursor(DocPos(0, 3))
            assertEquals(DocPos(0, 3), editor.cursor)
            assertEquals(DocPos(0, 3), editor.selectionAnchor)
        }
    }
}
