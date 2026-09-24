package cn.enaium.lsp.edit

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import kotlin.test.Test
import kotlin.test.assertTrue

/** Typing at the document's end must scroll the view down (soft follow). */
class BottomScrollTest {

    private fun expectedScroll(after: Float, before: Float): Boolean = after > before

    @Test
    fun typingAtEndScrollsDown() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(600f, 300f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(ImTextureID(0uL))

            val text = (1..100).joinToString("\n") { "line $it" }
            val editor = Editor(initialText = text, language = Language.kotlin)

            fun frame(): Float {
                ImGui.newFrame()
                ImGui.begin("##win")
                editor.render("ed", ImVec2(500f, 150f))
                val sy = editor.scrollOffsetY()
                println("PROBE editorScroll=" + sy +
                    " contentH=" + editor.contentHeightForTest + " lineH=" + editor.lineHeightPx() +
                    " viewH=" + editor.viewHeightForTest + " cursor=" + editor.cursor)
                ImGui.end()
                ImGui.render()
                return sy
            }

            frame()
            frame()
            // Move the caret to the document end, then add a NEW LINE at the
            // bottom: the view must follow down.
            editor.setCursor(editor.endOfDocument())
            frame()
            val before = frame()
            editor.queueTextInput("\nnext")
            // Frame 1 applies the edit (its dummy still uses the old
            // content height), frame 2's clamp re-arms the follow, frame 3
            // sees the updated scroll maximum and completes the scroll.
            frame()
            frame()
            val after = frame()
            println("PROBE scroll before=$before after=$after cursor=${editor.cursor}")
            assertTrue(expectedScroll(after, before), "adding a line at the end must scroll (before=$before after=$after)")
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
