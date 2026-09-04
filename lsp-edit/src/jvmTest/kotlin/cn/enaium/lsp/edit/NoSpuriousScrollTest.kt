package cn.enaium.lsp.edit

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Diagnostic: reproduce the kotlinlsp example layout (editor child with a
 * fixed height) and assert the vertical scroll range is exactly zero when
 * the document fits the viewport.
 */
class NoSpuriousScrollTest {

    @Test
    fun noVerticalScrollWhenContentFits() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)

            // Enough lines to roughly fill a 650px editor but not overflow.
            val lines = (0 until 40).joinToString("\n") { "fun f$it() = $it" }
            val editor = Editor(initialText = lines, language = Language.kotlin)

            ImGui.newFrame()
            ImGui.begin("##win")
            // Same shape as the example: fixed-height editor child.
            editor.render("testEditor", ImVec2(-1f, 650f))
            ImGui.end()
            ImGui.render()

            // Re-open the child and read its actual scroll max.
            ImGui.newFrame()
            ImGui.begin("##win")
            ImGui.beginChild("testEditor", ImVec2(-1f, 650f))
            val maxY = ImGui.getScrollMaxY()
            ImGui.endChild()
            ImGui.end()
            ImGui.render()
            println("DBG child scrollMaxY=$maxY")
            assertTrue(maxY <= 1f, "spurious vertical scroll: scrollMaxY=$maxY")
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
