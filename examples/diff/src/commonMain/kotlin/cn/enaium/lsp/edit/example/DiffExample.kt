package cn.enaium.lsp.edit.example

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.EditorMarker
import cn.enaium.lsp.edit.Language
import cn.enaium.lsp.edit.diff.Diff
import cn.enaium.lsp.edit.diff.DiffKind
import cn.enaium.lsp.edit.diff.DiffView

/**
 * A diff example with two display modes:
 *
 * - "Side by side": two versions of a small Kotlin file rendered with the
 *   lsp-edit [DiffView] (old on the left, new on the right).
 * - "Unified": one editor showing the diff as `-`/`+` lines — removed lines
 *   in red, added lines in green, context in the default color.
 *
 * The mode is toggled with the button in the top-left corner.
 */
fun runDiffExample(frames: Int = Int.MAX_VALUE) {
    var view: DiffView? = null
    var unified: UnifiedDiffEditor? = null
    var unifiedMode = false
    SdlRendererApp.run(
        title = "lsp-edit diff example",
        frames = frames,
        init = { _ ->
            view = DiffView(SAMPLE_BEFORE, SAMPLE_AFTER)
            unified = UnifiedDiffEditor(SAMPLE_BEFORE, SAMPLE_AFTER)
        },
        draw = { _ ->
            val v = view ?: return@run
            val u = unified ?: return@run
            // Fullscreen diff window.
            val display = ImGui.getIO().displaySize
            ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
            ImGui.setNextWindowSize(display, ImGuiCond.ALWAYS)
            ImGui.begin(
                "Diff (old -> new)",
                null,
                ImGuiWindowFlags.NO_MOVE or
                    ImGuiWindowFlags.NO_RESIZE or
                    ImGuiWindowFlags.NO_TITLE_BAR or
                    ImGuiWindowFlags.NO_COLLAPSE or
                    ImGuiWindowFlags.NO_SAVED_SETTINGS,
            )
            if (ImGui.button(if (unifiedMode) "Switch to side-by-side" else "Switch to unified")) {
                unifiedMode = !unifiedMode
            }
            ImGui.sameLine()
            ImGui.text(if (unifiedMode) "Unified: - removed (red), + added (green)." else "Side by side.")
            if (unifiedMode) {
                u.render()
            } else {
                v.render("##diff", ImVec2(-1f, -1f))
            }
            ImGui.end()
        },
        close = { view = null },
    )
}

/**
 * Unified diff in a single [Editor]: every diff line is prefixed with
 * `-` (removed, red), `+` (added, green) or ` ` (context), and the editor
 * shows the whole document scrollable/selectable like normal code.
 */
private class UnifiedDiffEditor(private val oldText: String, private val newText: String) {
    private val removedColor: Long = 0xFFFF6B6BL // soft red
    private val addedColor: Long = 0xFF6BCB77L // soft green

    private val editor = Editor(initialText = buildText(), language = Language.kotlin)
        .apply { readOnly = true }

    /** One Diff.compute pass drives both the text and the line colors. */
    private fun buildText(): String {
        val sb = StringBuilder()
        for (line in Diff.compute(oldText, newText)) {
            when (line.kind) {
                DiffKind.REMOVED -> sb.append('-')
                DiffKind.ADDED -> sb.append('+')
                DiffKind.CONTEXT -> sb.append(' ')
            }
            sb.append(line.text).append('\n')
        }
        return sb.toString()
    }

    private fun updateMarkers() {
        val markers = LinkedHashMap<Int, EditorMarker>()
        val lines = Diff.compute(oldText, newText)
        for ((i, line) in lines.withIndex()) {
            when (line.kind) {
                DiffKind.REMOVED -> markers[i] = EditorMarker(
                    lineNumberColor = removedColor,
                    textColor = removedColor,
                )
                DiffKind.ADDED -> markers[i] = EditorMarker(
                    lineNumberColor = addedColor,
                    textColor = addedColor,
                )
                DiffKind.CONTEXT -> Unit
            }
        }
        editor.markers = markers
    }

    fun render() {
        updateMarkers()
        editor.render("##unified")
    }
}

private val SAMPLE_BEFORE = """
    |fun main() {
    |    val origin = Point(0.0, 0.0)
    |    val target = Point(3.0, 4.0)
    |    println("distance = ${'$'}{distance(origin, target)}")
    |}
    |
    |fun distance(a: Point, b: Point): Double {
    |    val dx = a.x - b.x
    |    val dy = a.y - b.y
    |    return sqrt(dx * dx + dy * dy)
    |}
    |""".trimMargin()

private val SAMPLE_AFTER = """
    |fun main() {
    |    val origin = Point(0.0, 0.0)
    |    val target = Point(3.0, 4.0)
    |    println("length = ${'$'}{target.length()}")
    |}
    |
    |data class Point(val x: Double, val y: Double) {
    |    fun length() = sqrt(x * x + y * y)
    |}
    |""".trimMargin()
