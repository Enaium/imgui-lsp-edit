package cn.enaium.lsp.edit.example

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.lsp.edit.diff.DiffView

/**
 * A side-by-side diff example: two versions of a small Kotlin file rendered
 * with the lsp-edit [DiffView] (old on the left, new on the right).
 */
fun runDiffExample(frames: Int = Int.MAX_VALUE) {
    var view: DiffView? = null
    SdlRendererApp.run(
        title = "lsp-edit diff example",
        frames = frames,
        init = { _ ->
            view = DiffView(SAMPLE_BEFORE, SAMPLE_AFTER)
        },
        draw = { _ ->
            val v = view ?: return@run
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
            v.render("##diff")
            ImGui.end()
        },
        close = { view = null },
    )
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
