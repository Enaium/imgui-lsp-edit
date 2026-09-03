package cn.enaium.lsp.edit

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the cumulative cursor/text drift bug: with a
 * fractional font size (e.g. `13f * density` where density is non-integer),
 * summing per-character `calcTextSize` drifts linearly from what
 * `RenderText` actually draws, so a long line shifts the cursor more and
 * more. Whole-string prefix measurement stays bounded.
 */
class AdvanceProbe {
    private fun runProbe(sizePixels: Float, density: Float, label: String) {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            val fonts = io.fonts
            fonts.addFontDefault(cn.enaium.imgui.ImFontConfig(sizePixels = sizePixels, rasterizerDensity = density))
            check(fonts.build()) { "font build failed" }
            fonts.setTexID(0)

            ImGui.newFrame()
            val text = "h" + "e".repeat(49) // 50 chars
            val startX = 100f

            // Ground truth: the whole string rendered in a single DrawText.
            ImGui.getForegroundDrawList().DrawText(ImVec2(startX, 100f), text, 0xFFFFFFFF.toInt())
            ImGui.render()

            val dd = ImGui.getDrawData()
            val xs = ArrayList<Float>()
            for (l in 0 until dd.cmdListsCount) {
                val list = dd.cmdList(l)
                val vtx = list.copyVtx(0, list.vtxCount)
                for (i in vtx.positions.indices step 2) {
                    val x = vtx.positions[i]
                    val y = vtx.positions[i + 1]
                    if (y in 92f..108f && x in 90f..900f) xs.add(x)
                }
            }
            xs.sort()
            // Every glyph quad contributes a pair (left edge, right edge).
            val leftEdges = xs.filterIndexed { i, _ -> i % 2 == 0 }

            // Predicted positions: whole-string prefixes (current editor
            // approach) vs naive per-char accumulation (the old approach).
            val prefixPred = FloatArray(text.length + 1)
            val perCharPred = FloatArray(text.length + 1)
            var acc = 0f
            for (i in text.indices) {
                prefixPred[i + 1] = ImGui.calcTextSize(text.substring(0, i + 1)).x
                acc += ImGui.calcTextSize(text[i].toString()).x
                perCharPred[i + 1] = acc
            }

            var maxPrefixErr = 0f
            var maxPerCharErr = 0f
            for (k in leftEdges.indices) {
                val actual = leftEdges[k]
                maxPrefixErr = maxOf(maxPrefixErr, abs(actual - (startX + prefixPred[k])))
                maxPerCharErr = maxOf(maxPerCharErr, abs(actual - (startX + perCharPred[k])))
            }

            val out = java.io.File("/tmp/lspedit_probe_$label.txt")
            out.writeText(
                "[$label] size=$sizePixels density=$density glyphs=${leftEdges.size}\n" +
                    "  max prefix error:   $maxPrefixErr\n" +
                    "  max per-char error: $maxPerCharErr\n",
            )
            // Bounded: never drifts more than ~1.5px no matter the line
            // length. The old per-char approach blows past this quickly
            // (≈10px over 50 chars at 19.5px).
            assertTrue(
                maxPrefixErr <= 2f,
                "prefix measurement drifted $maxPrefixErr px from rendered glyphs ($label); " +
                    "lineAdvance must keep whole-string prefix measurement",
            )
            if (label == "fractional") {
                assertTrue(
                    maxPerCharErr > 3f,
                    "sanity: per-char accumulation should drift but measured $maxPerCharErr px",
                )
            }
        } finally {
            ImGui.destroyContext(ctx)
        }
    }

    @Test
    fun renderProbe() {
        runProbe(13f, 1f, "integer")
        runProbe(19.5f, 1.5f, "fractional")
    }
}

/**
 * Inlay hints occupy visual space (text is pushed right) but must NOT become
 * cursor-addressable columns: the caret lands on real text gaps, and clicks
 * on a hint snap to the nearest text gap.
 */
class InlayHintCursorTest {
    private fun withContext(block: () -> Unit) {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(cn.enaium.imgui.ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)
            // imgui-kmp >= 1.0.8 requires an active frame before any
            // CalcTextSize; otherwise the font's glyph data is not ready
            // and the native call segfaults.
            ImGui.newFrame()
            block()
        } finally {
            ImGui.destroyContext(ctx)
        }
    }

    @Test
    fun hintsPushTextButNotColumns() = withContext {
        val e = Editor("abcde", language = Language.cpp)
        // A hint "x: " sits before column 3 ('d').
        e.inlayHintsProvider = { line ->
            if (line == 0) listOf(cn.enaium.lsp.edit.EditorInlayHint(DocPos(0, 3), "x: ")) else emptyList()
        }

        // Visual position of column 3 includes the hint; the raw text
        // advance does not — the caret is drawn AFTER the hint.
        val hintWidth = ImGui.calcTextSize("x: ").x
        assertEquals(
            e.lineAdvance(0, 3) + hintWidth,
            e.lineVisualAdvance(0, 3),
            0.01f,
        )
        assertEquals(e.lineAdvance(0, 3), e.lineVisualAdvance(0, 3) - hintWidth, 0.01f)

        // Clicking INSIDE the hint (at hint start + small offset) snaps to
        // column 3 (the text gap right after the hint), never to a
        // pseudo-column inside the hint.
        val clickInsideHint = e.lineVisualAdvance(0, 3) - hintWidth * 0.5f
        assertEquals(3, e.columnAtX(0, clickInsideHint))

        // Clicking just BEFORE the hint still lands on column 3's gap.
        val clickBeforeHint = e.lineVisualAdvance(0, 3) - hintWidth - 1f
        assertEquals(3, e.columnAtX(0, clickBeforeHint))

        // Clicking well before that lands on earlier columns as usual.
        assertEquals(0, e.columnAtX(0, 1f))
        assertEquals(5, e.columnAtX(0, 1000f))
    }
}

/**
 * Smoke test for the hover markdown renderer (imgui-kmp >= 1.0.8):
 * creating the config, rendering markdown into a tooltip and destroying
 * the config must not crash or produce empty output.
 */
class MarkdownHoverTest {
    @Test
    fun rendersMarkdown() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(cn.enaium.imgui.ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)
            // imgui-kmp >= 1.0.8 requires an active frame before any
            // CalcTextSize; otherwise the font's glyph data is not ready
            // and the native call segfaults.
            ImGui.newFrame()

            val config = cn.enaium.imgui.extensions.markdown.Markdown.create()
            cn.enaium.imgui.extensions.markdown.Markdown.setFormatFlags(
                config,
                cn.enaium.imgui.extensions.markdown.MdFormatFlags.COMMON_MARK_ALL,
            )

            // A newly created window is not submitted to the draw data in
            // its first frame (imgui hides it for one frame), so warm up
            // the frame once before asserting on the markdown content.
            ImGui.begin("##markdownTest")
            ImGui.end()
            ImGui.render()
            ImGui.newFrame()

            ImGui.begin("##markdownTest")
            val before = ImGui.getCursorPos().y
            cn.enaium.imgui.extensions.markdown.Markdown.render(config, "**hover**  \n`code`")
            val after = ImGui.getCursorPos().y
            ImGui.end()
            config.close()

            ImGui.render()
            val dd = ImGui.getDrawData()
            assertTrue(after > before, "markdown render advanced the cursor")
            assertTrue(dd.cmdListsCount > 0, "markdown render produced no draw lists")
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}


/**
 * Regression: the hover markdown renderer must wrap at a readable width,
 * not one word per line. The tooltip is auto-sized, so LspEditor constrains
 * it to a minimum width; here we assert the draw data actually has far
 * fewer text lines than words for a long sentence.
 */
class MarkdownWrapTest {
    @Test
    fun wrapsAtTooltipWidth() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(cn.enaium.imgui.ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)
            io.addMousePosEvent(640f, 400f)
            ImGui.newFrame()

            val config = cn.enaium.imgui.extensions.markdown.Markdown.create()
            val sentence = "alpha beta gamma delta epsilon zeta eta theta iota kappa " +
                "lambda mu nu xi omicron pi rho sigma tau upsilon phi chi psi omega"
            val words = sentence.split(" ").size

            repeat(2) { frame ->
                if (frame > 0) ImGui.newFrame()
                // Same constraint LspEditor.renderHoverTooltip applies.
                ImGui.setNextWindowSizeConstraints(
                    ImVec2(320f, 0f),
                    ImVec2(Float.MAX_VALUE, Float.MAX_VALUE),
                )
                ImGui.beginTooltip()
                cn.enaium.imgui.extensions.markdown.Markdown.render(config, sentence)
                ImGui.endTooltip()
                ImGui.render()
                if (frame == 1) {
                    val dd = ImGui.getDrawData()
                    assertTrue(dd.cmdListsCount > 0, "tooltip produced no draw lists")
                    val ys = LinkedHashSet<Int>()
                    for (l in 0 until dd.cmdListsCount) {
                        val list = dd.cmdList(l)
                        val vtx = list.copyVtx(0, list.vtxCount)
                        for (i in 1 until vtx.positions.size step 2) {
                            ys.add((vtx.positions[i] / 4f).toInt() * 4) // quantize
                        }
                    }
                    // A 20-word sentence wrapped at ~40 chars/line is ~4-5
                    // lines; per-word wrapping would be ~20 distinct y rows.
                    assertTrue(
                        ys.size < words,
                        "markdown wrapped too eagerly: ${ys.size} rows for $words words",
                    )
                }
            }
            config.close()
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}

/**
 * Regression: inlay hints render (and are measured) with the smaller
 * [cn.enaium.lsp.edit.Editor.inlayHintFont] when one is set, so the visual
 * layout, cursor and click mapping all agree on hint widths.
 */
class InlayHintFontTest {
    @Test
    fun usesSmallerFontForHints() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            val mainFont = io.fonts.addFontDefault(cn.enaium.imgui.ImFontConfig(sizePixels = 13f))
            val smallFont = io.fonts.addFontDefault(cn.enaium.imgui.ImFontConfig(sizePixels = 10f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)
            ImGui.newFrame()

            fun width(font: cn.enaium.imgui.ImFont, label: String): Float {
                ImGui.pushFont(font)
                val w = ImGui.calcTextSize(label).x
                ImGui.popFont()
                return w
            }
            val mainW = width(mainFont, "x: ")
            val smallW = width(smallFont, "x: ")
            assertTrue(smallW < mainW, "expected 10px font narrower than 13px (got $smallW vs $mainW)")

            val e = Editor("abcde", language = Language.cpp)
            e.inlayHintsProvider = { line ->
                if (line == 0) listOf(cn.enaium.lsp.edit.EditorInlayHint(DocPos(0, 3), "x: ")) else emptyList()
            }
            e.inlayHintFont = smallFont

            // Visual advance of column 3 includes the SMALL hint width.
            assertEquals(
                e.lineAdvance(0, 3) + smallW,
                e.lineVisualAdvance(0, 3),
                0.01f,
            )
            // Clicking inside the hint snaps to column 3 (text gap after it).
            assertEquals(3, e.columnAtX(0, e.lineVisualAdvance(0, 3) - smallW * 0.5f))

            // Without a hint font the width would be the main font's — the
            // layout must NOT use that for a line that has a hint font.
            e.inlayHintFont = null
            assertEquals(
                e.lineAdvance(0, 3) + mainW,
                e.lineVisualAdvance(0, 3),
                0.01f,
            )
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
