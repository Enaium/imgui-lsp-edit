package cn.enaium.lsp.edit

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Clicking the line-number strip toggles a breakpoint, and the host hears
 * about it — the gutter is where IDEs expect that toggle to live, so a host
 * that never sees a breakpoint marker has nothing to send to a debug adapter.
 */
class GutterBreakpointTest {

    @Test
    fun markersKeepTheirState() {
        val ctx = ImGui.createContext()
        try {
            val editor = Editor(initialText = "a\nb\nc", language = Language.kotlin)
            editor.setBreakpointMarkers(
                listOf(
                    EditorBreakpoint(line = 1, verified = true),
                    EditorBreakpoint(line = 2, verified = false, conditional = true),
                    EditorBreakpoint(line = 3, enabled = false, logpoint = true),
                ),
            )
            assertEquals(setOf(1, 2, 3), editor.getBreakpointLines())
            assertTrue(editor.hasBreakpoint(0))
            assertEquals(true, editor.breakpointAt(0)?.verified)
            assertEquals(false, editor.breakpointAt(1)?.verified)
            assertTrue(editor.breakpointAt(1)?.conditional == true)
            assertEquals(false, editor.breakpointAt(2)?.enabled)
            assertTrue(editor.breakpointAt(2)?.logpoint == true)
            // Plain lines still work for hosts without adapter state.
            editor.setBreakpoints(setOf(2))
            assertEquals(null, editor.breakpointAt(1)?.verified)
            assertEquals(setOf(2), editor.getBreakpointLines())
        } finally {
            ImGui.destroyContext(ctx)
        }
    }

    @Test
    fun everyStateHasItsOwnIcon() {
        val icons = listOf(
            EditorBreakpoint(line = 1),
            EditorBreakpoint(line = 1, verified = true),
            EditorBreakpoint(line = 1, verified = false),
            EditorBreakpoint(line = 1, enabled = false),
            EditorBreakpoint(line = 1, logpoint = true),
        ).map { BreakpointIcons.iconFor(it) }
        assertEquals(icons.size, icons.toSet().size, "each breakpoint state needs a distinct icon")
    }

    @Test
    fun gutterClickTogglesBreakpoint() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(800f, 600f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(ImTextureID(0uL))

            val editor = Editor(initialText = "a\nb\nc\nd", language = Language.kotlin)
            var reported: Set<Int>? = null
            editor.onBreakpointsChange = { reported = it }

            fun frame() {
                ImGui.newFrame()
                ImGui.begin("##win")
                editor.render("ed", ImVec2(700f, 300f))
                ImGui.end()
                ImGui.render()
            }

            // Click with the pointer parked, then pressed, then released —
            // ImGui needs the hover before the press to hit an item.
            fun click(x: Float, y: Float) {
                io.addMousePosEvent(x, y)
                frame()
                io.addMouseButtonEvent(0, true)
                frame()
                io.addMouseButtonEvent(0, false)
                frame()
            }

            frame()
            // 20px left of the text area: inside the line-number strip, clear
            // of the fold marker that owns the gutter's right edge.
            val gutterX = editor.caretScreenX() - 20f
            val secondLineY = editor.caretScreenY() + editor.lineHeightPx() * 1.5f

            click(gutterX, secondLineY)
            assertTrue(editor.hasBreakpoint(1), "the gutter click sets a breakpoint on that line")
            assertEquals(setOf(2), reported, "the host is told which lines have breakpoints")

            click(gutterX, secondLineY)
            assertFalse(editor.hasBreakpoint(1), "clicking the same line again clears it")
            assertEquals(emptySet(), reported)

            // The text area still moves the caret instead of toggling.
            click(editor.caretScreenX() + 30f, secondLineY)
            assertFalse(editor.hasBreakpoint(1), "a click in the text must not set a breakpoint")
            assertEquals(1, editor.cursor.line, "a click in the text moves the caret")
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
