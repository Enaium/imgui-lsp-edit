package cn.enaium.lsp.edit

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import kotlin.test.Test
import kotlin.test.assertEquals

/** Typing with the mouse parked over the text must not fire a hover. */
class HoverSuppressTest {

    @Test
    fun typingSuppressesParkedHover() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(800f, 600f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)

            val editor = Editor(initialText = "hello world", language = Language.kotlin)
            var hoverFires = 0
            editor.onHover = { hoverFires++ }

            fun frame(step: Float = 1f / 60f) {
                io.deltaTime = step
                ImGui.newFrame()
                ImGui.begin("##win")
                editor.render("ed", ImVec2(700f, 300f))
                ImGui.end()
                ImGui.render()
            }

            // Park the mouse over the first character.
            frame()
            val x = editor.caretScreenX() + 2f
            val y = editor.caretScreenY() + 2f
            ImGui.getIO().addMousePosEvent(x, y)
            frame()
            frame(0.1f)
            frame(0.1f)
            frame(0.1f)
            frame(0.1f)
            val before = hoverFires

            // Type with the mouse parked: the pending hover must not fire.
            editor.queueTextInput("x")
            frame()
            io.addMousePosEvent(x, y)
            frame(0.2f)
            frame(0.2f)
            frame(0.2f)
            println("PROBE hover before=$before after=$hoverFires")
            assertEquals(before, hoverFires, "typing must suppress the parked hover")
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
