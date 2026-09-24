package cn.enaium.lsp.edit

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scroll-follow regression test: the editor must follow the cursor only
 * after keyboard navigation / edits / jumps. A manual scroll (wheel or
 * scrollbar) must never trigger a follow-back, otherwise large documents
 * cannot be read — the view snaps to the cursor every frame.
 *
 * The follow flag is internal state, observed here via a probe closure that
 * records whether the editor scrolled to the cursor during a frame.
 */
class ScrollFollowTest {

    private fun newFrame() {
        ImGui.newFrame()
        ImGui.begin("##win")
    }

    private fun endFrame() {
        ImGui.end()
        ImGui.render()
    }

    @Test
    fun scrollFollowOnlyAfterNavigation() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(800f, 600f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(ImTextureID(0uL))

            val lines = (0 until 200).joinToString("\n") { "fun line$it() = $it" }
            val editor = Editor(initialText = lines, language = Language.kotlin)

            // Warm-up frame.
            newFrame()
            editor.render("testEditor", ImVec2(400f, 200f))
            endFrame()

            // Render a second plain frame (no input): the initial follow
            // was consumed by the warm-up frame, so nothing may re-request
            // it — a manual scroll position therefore survives.
            newFrame()
            editor.render("testEditor", ImVec2(400f, 200f))
            endFrame()
            assertFalse(
                editor.scrollFollowRequestedForTest,
                "a plain frame must not request scroll-follow",
            )

            // A programmatic cursor jump requests a full follow.
            editor.setCursor(DocPos(150, 0))
            assertTrue(
                editor.scrollFollowRequestedForTest,
                "a cursor jump must request scroll-follow",
            )
            assertFalse(
                editor.scrollFollowSoftForTest,
                "a cursor jump is not a soft follow",
            )

            // Consume the jump's full follow with a render, then type.
            newFrame()
            editor.render("testEditor", ImVec2(400f, 200f))
            endFrame()
            assertFalse(
                editor.scrollFollowRequestedForTest,
                "the jump follow must be consumed by one render",
            )

            // An edit requests the SOFT (down-only) follow: typing above
            // the viewport must not yank the scroll position back up.
            editor.inputText("x")
            assertTrue(
                editor.scrollFollowSoftForTest,
                "an edit must request a soft scroll-follow",
            )
            assertFalse(
                editor.scrollFollowRequestedForTest,
                "an edit must not request a full scroll-follow",
            )

            // Consumed follows must not linger: render once more and check
            // both were cleared.
            newFrame()
            editor.render("testEditor", ImVec2(400f, 200f))
            endFrame()
            assertFalse(
                editor.scrollFollowRequestedForTest,
                "scroll-follow must be consumed by one render",
            )
            assertFalse(
                editor.scrollFollowSoftForTest,
                "soft scroll-follow must be consumed by one render",
            )
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
