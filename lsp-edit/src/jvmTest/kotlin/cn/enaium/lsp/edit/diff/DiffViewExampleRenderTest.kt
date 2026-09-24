package cn.enaium.lsp.edit.diff

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Diagnostic: does DiffView render the example texts at all? Renders it
 * headlessly and inspects the emitted draw commands (text and filled rects)
 * plus their coordinates.
 */
class DiffViewExampleRenderTest {

    private val before = """
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

    private val after = """
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

    @Test
    fun rendersExampleDiff() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(ImTextureID(0uL))

            val diff = DiffView(before, after)
            val lines = Diff.compute(before, after)
            println("DBG diff lines=${lines.size}")
            for (l in lines.take(12)) {
                println("DBG   ${l.kind} old=${l.oldLine} new=${l.newLine} text=${l.text.take(40)}")
            }
            assertTrue(lines.isNotEmpty(), "Diff.compute returned no lines")

            // Warm-up frame.
            ImGui.newFrame()
            ImGui.begin("##win")
            diff.render("##diff")
            ImGui.end()
            ImGui.render()
            ImGui.newFrame()

            // Real frame: count draw commands and their bounds.
            ImGui.begin("##win")
            diff.render("##diff")
            ImGui.end()
            ImGui.render()

            val dd = ImGui.getDrawData()
            var cmdCount = 0
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            for (ci in 0 until dd.cmdListsCount) {
                val list = dd.cmdList(ci)
                val count = list.cmdCount
                for (j in 0 until count) {
                    val c = list.cmd(j)
                    val clip = c.clipRect
                    cmdCount++
                    minY = minOf(minY, clip.y)
                    maxY = maxOf(maxY, clip.y + clip.w)
                    minX = minOf(minX, clip.x)
                    maxX = maxOf(maxX, clip.x + clip.z)
                }
            }
            println("DBG cmdCount=$cmdCount bounds=($minX,$minY)-($maxX,$maxY)")
            assertTrue(cmdCount > 0, "no draw commands emitted")
            assertTrue(maxX > 0f && maxY > 0f, "draw commands outside the screen: ($minX,$minY)-($maxX,$maxY)")
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
