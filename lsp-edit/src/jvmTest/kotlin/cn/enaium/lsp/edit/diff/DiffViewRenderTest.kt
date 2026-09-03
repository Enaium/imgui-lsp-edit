package cn.enaium.lsp.edit.diff

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test for the side-by-side diff renderer: creating a context,
 * rendering a diff of two texts and destroying the view must not crash
 * and must produce draw lists.
 */
class DiffViewRenderTest {

    @Test
    fun rendersSideBySideDiff() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(cn.enaium.imgui.ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)
            ImGui.newFrame()

            val view = DiffView(
                "fun main() {\n    println(\"hi\")\n}",
                "fun main() {\n    println(\"hello\")\n    println(\"bye\")\n}",
            )

            // Warm-up frame: a newly created child window is not submitted
            // to the draw data in its first frame.
            ImGui.begin("##diffTest")
            view.render("##diff")
            ImGui.end()
            ImGui.render()
            ImGui.newFrame()

            ImGui.begin("##diffTest")
            view.render("##diff")
            ImGui.end()
            ImGui.render()

            val dd = ImGui.getDrawData()
            assertTrue(dd.cmdListsCount > 0, "diff view produced no draw lists")
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
