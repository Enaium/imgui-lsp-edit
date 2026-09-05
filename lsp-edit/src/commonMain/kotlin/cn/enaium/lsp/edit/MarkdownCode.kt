package cn.enaium.lsp.edit

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.extensions.markdown.Markdown
import cn.enaium.imgui.extensions.markdown.MarkdownConfigHandle

/**
 * Markdown rendering with fenced code-block support.
 *
 * The imgui-kmp Markdown extension renders plain Markdown but has no hook
 * for ```fenced``` code blocks (the underlying imgui_markdown has no code
 * callback). [render] splits the input at fenced blocks, renders the prose
 * segments with [Markdown.render] and draws each code block with the
 * built-in [SyntaxHighlighter] in a read-only, theme-colored style: no line
 * numbers, no editing, no scrolling — only syntax highlighting on a dim
 * code background.
 */
object MarkdownCode {

    /**
     * Renders [markdown] into the current ImGui window.
     *
     * @param config the Markdown config handle (as passed to [Markdown.render])
     * @param markdown the source text
     * @param language fallback [Language] for fenced blocks whose info string
     *   is missing or unknown; null renders code blocks as plain text
     * @param palette editor palette used for code-block colors (defaults to
     *   [EditorPalette.dark])
     */
    fun render(
        config: MarkdownConfigHandle,
        markdown: String,
        language: Language? = null,
        palette: Array<Color> = EditorPalette.dark,
    ) {
        val segments = splitFencedBlocks(markdown)
        if (segments.size == 1) {
            // No fenced blocks: plain path, identical to Markdown.render.
            Markdown.render(config, markdown)
            return
        }
        for (segment in segments) {
            when (segment) {
                is FencedBlock -> renderCodeBlock(segment, language, palette)
                is Prose -> {
                    if (segment.text.isNotBlank()) Markdown.render(config, segment.text)
                }
            }
            // A small gap after every segment keeps prose and code apart.
            ImGui.spacing()
        }
    }

    // ==================== fenced block splitting ====================

    internal sealed interface Segment
    internal data class Prose(val text: String) : Segment
    internal data class FencedBlock(val info: String, val code: String) : Segment

    /**
     * Splits [markdown] into prose and ```fenced``` code segments.
     * The info string (language hint) after the opening fence is kept;
     * the closing fence line is dropped. Unclosed fences run to the end.
     */
    internal fun splitFencedBlocks(markdown: String): List<Segment> {
        val segments = ArrayList<Segment>()
        val lines = markdown.split('\n')
        val prose = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = fenceOf(line)
            if (fence != null) {
                if (prose.isNotEmpty()) {
                    segments.add(Prose(prose.toString()))
                    prose.clear()
                }
                val code = StringBuilder()
                i++
                while (i < lines.size && fenceOf(lines[i]) == null) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                // Skip the closing fence line (i now points at it or EOF).
                if (i < lines.size) i++
                segments.add(FencedBlock(fence, code.toString()))
            } else {
                if (prose.isNotEmpty()) prose.append('\n')
                prose.append(line)
                i++
            }
        }
        if (prose.isNotEmpty()) segments.add(Prose(prose.toString()))
        return segments
    }

    /** Returns the fence info string when [line] opens/closes a fenced block, else null. */
    private fun fenceOf(line: String): String? {
        val trimmed = line.trimEnd()
        if (!trimmed.startsWith("```")) return null
        if (trimmed.length < 3) return ""
        val rest = trimmed.substring(3).trim()
        // Closing fences carry no info string.
        return if (rest.isEmpty()) "" else rest
    }

    // ==================== code block rendering ====================

    /**
     * Draws a fenced code block read-only: a dim background, syntax-colored
     * text via [SyntaxHighlighter], no line numbers and no editing.
     */
    private fun renderCodeBlock(block: FencedBlock, fallback: Language?, palette: Array<Color>) {
        val lang = languageFor(block.info, fallback)
        val lines = block.code.split('\n')
        val lineHeight = ImGui.getTextLineHeight()
        val padding = 8f

        // Measure the widest line for the background width.
        var maxWidth = 0f
        for (line in lines) {
            val w = ImGui.calcTextSize(line).x
            if (w > maxWidth) maxWidth = w
        }
        val avail = ImGui.getContentRegionAvail().x
        val blockWidth = (maxWidth + padding * 2f).coerceAtMost(avail)
        val blockHeight = lineHeight * lines.size + padding * 2f

        val cursor = ImGui.getCursorScreenPos()
        val drawList = ImGui.getWindowDrawList()

        // Code background: a darker shade derived from the palette background.
        val bg = palette[PaletteIndex.BACKGROUND].toImGuiColor()
        val codeBg = darken(bg, 0.85f)
        drawList.DrawRectFilled(
            ImVec2(cursor.x, cursor.y),
            ImVec2(cursor.x + blockWidth, cursor.y + blockHeight),
            codeBg,
        )
        // A thin border to separate the block from prose.
        drawList.DrawRect(
            ImVec2(cursor.x, cursor.y),
            ImVec2(cursor.x + blockWidth, cursor.y + blockHeight),
            palette[PaletteIndex.LINE_NUMBER].toImGuiColor(),
        )

        // Advance the ImGui cursor past the drawn block so following prose
        // continues below it.
        val cur = ImGui.getCursorPos()
        ImGui.setCursorPos(ImVec2(cur.x, cur.y + blockHeight))

        // Draw each line with syntax colors.
        var state = TokenState.NONE
        val textColor = palette[PaletteIndex.TEXT].toImGuiColor()
        for ((row, line) in lines.withIndex()) {
            val y = cursor.y + padding + row * lineHeight
            if (lang != null) {
                val result = SyntaxHighlighter.tokenize(lang, line, state)
                state = result.carryState
                var x = cursor.x + padding
                var pos = 0
                for (span in result.spans) {
                    if (span.start > pos) {
                        drawList.DrawText(
                            ImVec2(x, y),
                            line.substring(pos, span.start.coerceAtMost(line.length)),
                            textColor,
                        )
                        x += ImGui.calcTextSize(line.substring(pos, span.start.coerceAtMost(line.length))).x
                    }
                    val end = span.end.coerceAtMost(line.length)
                    if (end > span.start) {
                        val chunk = line.substring(span.start, end)
                        drawList.DrawText(
                            ImVec2(x, y),
                            chunk,
                            palette[span.palette.coerceIn(0, PaletteIndex.COUNT - 1)].toImGuiColor(),
                        )
                        x += ImGui.calcTextSize(chunk).x
                    }
                    pos = end
                }
                if (pos < line.length) {
                    drawList.DrawText(
                        ImVec2(x, y),
                        line.substring(pos),
                        textColor,
                    )
                }
            } else {
                drawList.DrawText(
                    ImVec2(cursor.x + padding, y),
                    line,
                    textColor,
                )
            }
        }
    }

    /** Maps a fence info string to a [Language]; unknown names fall back. */
    private fun languageFor(info: String, fallback: Language?): Language? {
        val name = info.trim().lowercase().substringBefore(' ').substringBefore('\t')
        if (name.isEmpty()) return fallback
        return when (name) {
            "kotlin", "kt", "kts" -> Language.kotlin
            "python", "py" -> Language.python
            "json" -> Language.json
            "cpp", "c", "h", "hpp", "cxx", "java", "cs", "go", "rs", "ts", "js",
            "typescript", "javascript", "rust", "sql", "bash", "sh", "shell",
            "yaml", "yml", "toml", "xml", "html", "css", "gradle", "groovy",
            -> Language.cpp
            else -> fallback
        }
    }

    /**
     * Multiplies RGB by [factor] on an ImGui-packed 0xAABBGGRR color
     * (the format [Color.toImGuiColor] produces), keeping alpha.
     */
    private fun darken(color: Int, factor: Float): Int {
        val a = (color ushr 24) and 0xFF
        val b = (((color ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 0xFF)
        val g = (((color ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 0xFF)
        val r = ((color and 0xFF) * factor).toInt().coerceIn(0, 0xFF)
        return (a shl 24) or (b shl 16) or (g shl 8) or r
    }
}
