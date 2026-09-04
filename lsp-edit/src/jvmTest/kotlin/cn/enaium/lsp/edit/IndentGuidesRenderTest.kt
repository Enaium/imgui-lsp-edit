package cn.enaium.lsp.edit

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test for indent guides: with [Editor.showIndentGuides] enabled, a
 * line with leading spaces + a tab emits extra draw commands (dots + lines)
 * compared to the guides being off.
 */
class IndentGuidesRenderTest {

    private fun drawIndices(editor: Editor, size: ImVec2): Int {
        ImGui.newFrame()
        ImGui.begin("##win")
        editor.render("testEditor", size)
        ImGui.end()
        ImGui.render()
        val dd = ImGui.getDrawData()
        var idx = 0
        for (i in 0 until dd.cmdListsCount) {
            idx += dd.cmdList(i).idxCount
        }
        return idx
    }

    @Test
    fun indentGuidesAddDrawCommands() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(800f, 600f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)

            // Leading spaces and a tab on the first lines.
            val text = "    val a = 1\n\tval b = 2\nfun main() {}"
            val editor = Editor(initialText = text, language = Language.kotlin)

            // Warm-up.
            drawIndices(editor, ImVec2(400f, 200f))
            val off = drawIndices(editor, ImVec2(400f, 200f))

            editor.showIndentGuides = true
            drawIndices(editor, ImVec2(400f, 200f))
            val on = drawIndices(editor, ImVec2(400f, 200f))

            println("DBG indices off=$off on=$on")
            assertTrue(on > off, "indent guides must add geometry (off=$off on=$on)")
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
