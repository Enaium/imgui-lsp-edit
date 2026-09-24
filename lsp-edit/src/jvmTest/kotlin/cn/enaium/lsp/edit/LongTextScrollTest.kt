package cn.enaium.lsp.edit

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Diagnostic for "vertical content taller than the viewport renders
 * incompletely": simulate real wheel scrolling (the same mechanism a user
 * uses) and verify the child window scrolls and the document end is
 * reachable.
 */
class LongTextScrollTest {

    @Test
    fun verticalScrollCoversWholeDocument() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(800f, 600f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(ImTextureID(0uL))

            val lineCount = 200
            val lines = (0 until lineCount).joinToString("\n") { "fun line$it() = $it" }
            val editor = Editor(initialText = lines, language = Language.kotlin)

            // Warm-up frame.
            ImGui.newFrame()
            ImGui.begin("##win")
            editor.render("testEditor", ImVec2(400f, 200f))
            ImGui.end()
            ImGui.render()

            // Read the child's scroll range.
            ImGui.newFrame()
            ImGui.begin("##win")
            ImGui.beginChild("testEditor", ImVec2(400f, 200f))
            val maxY = ImGui.getScrollMaxY()
            ImGui.endChild()
            ImGui.end()
            ImGui.render()

            // Simulate the user wheeling down over the editor, one notch per
            // frame. Every frame is a real editor render (no intermediate
            // content-less frames, which would clamp the scroll state away).
            var scrollY = 0f
            for (i in 0 until 30) {
                io.addMousePosEvent(200f, 100f)
                io.addMouseWheelEvent(0f, -2f)
                ImGui.newFrame()
                ImGui.begin("##win")
                editor.render("testEditor", ImVec2(400f, 200f))
                ImGui.end()
                ImGui.render()
                scrollY = editor.scrollOffsetY()
            }
            assertTrue(
                scrollY > 0f,
                "wheel scrolling does not move the view: scrollY=$scrollY",
            )

            // Keep wheeling until the bottom; the document end must be
            // reachable (scrollY == maxY).
            for (i in 0 until 120) {
                if (scrollY >= maxY - 1f) break
                io.addMousePosEvent(200f, 100f)
                io.addMouseWheelEvent(0f, -5f)
                ImGui.newFrame()
                ImGui.begin("##win")
                editor.render("testEditor", ImVec2(400f, 200f))
                ImGui.end()
                ImGui.render()
                scrollY = editor.scrollOffsetY()
            }
            assertTrue(
                scrollY >= maxY - 1f,
                "document end unreachable: scrollY=$scrollY maxY=$maxY",
            )

            // At the bottom, the last line's caret must be inside the
            // viewport (screen coordinates, viewport is 200px tall).
            editor.setCursor(DocPos(lineCount - 1, 0))
            ImGui.newFrame()
            ImGui.begin("##win")
            editor.render("testEditor", ImVec2(400f, 200f))
            ImGui.end()
            ImGui.render()
            val topY = editor.viewportTopY()
            val caretY = editor.caretScreenY()
            assertTrue(
                caretY in topY..(topY + 200f),
                "last-line caret outside the viewport: caretY=$caretY topY=$topY",
            )
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
