package cn.enaium.lsp.edit.example

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.JetBrainsThemes
import cn.enaium.lsp.edit.syntax.treesitter.TreeSitterHighlighter

/**
 * Tree-sitter highlighting showcase: an editor whose highlighting is driven
 * by tree-sitter grammars (via tree-sitter-languages-kmp + ktreesitter),
 * with a per-language highlight query from the lsp-edit library. A combo
 * box switches between all 34 bundled grammars.
 *
 * Run with `./gradlew :examples:syntax:jvmRun` or the native binaries;
 * pass `--frames N` / `IMGUI_KMP_FRAMES=N` to exit after N frames (headless
 * CI runs).
 */
fun runSyntaxExample(frames: Int = Int.MAX_VALUE, fallbackFontPath: String? = null) {
    var app: SyntaxExampleApp? = null
    SdlRendererApp.run(
        title = "lsp-edit tree-sitter syntax example",
        frames = frames,
        fallbackFontPath = fallbackFontPath,
        init = { _ -> app = SyntaxExampleApp() },
        draw = { frame -> app?.draw(frame) },
        close = { app?.close() },
        onTextInput = { text ->
            val a = app
            if (a != null && a.editor.isFocused) {
                a.editor.inputText(text)
                true
            } else {
                false
            }
        },
        textInputWanted = { app?.editor?.isFocused == true },
    )
}

/** The demo UI: language combo + tree-sitter-highlighted editor. */
private class SyntaxExampleApp {

    private val specs = TreeSitterLanguageSpec.entries

    /** Current selection index into [specs]. */
    private val current = IntArray(1)

    /** Current JetBrains theme index into [JetBrainsThemes.all]. */
    private val themeIndex = IntArray(1)

    private var highlighter: TreeSitterHighlighter? = null

    val editor = Editor(initialText = "")

    init {
        select(0)
    }

    private fun select(index: Int) {
        current[0] = index
        val spec = specs[index]
        val sample = spec.sample
        editor.setText(sample)
        val next = try {
            TreeSitterHighlighter(spec.language(), spec.query)
        } catch (e: Throwable) {
            println("tree-sitter init failed for ${spec.displayName}: ${e.message}")
            null
        }
        next?.setText(sample)
        highlighter = next
        editor.tokenProvider = { line -> next?.spansForLine(line) }
    }

    fun draw(frame: Int) {
        // Fullscreen editor: fill the whole window, no decorations.
        val display = ImGui.getIO().displaySize
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(display, ImGuiCond.ALWAYS)
        ImGui.begin(
            "Tree-sitter syntax (lsp-edit)",
            null,
            ImGuiWindowFlags.NO_MOVE or
                ImGuiWindowFlags.NO_RESIZE or
                ImGuiWindowFlags.NO_TITLE_BAR or
                ImGuiWindowFlags.NO_COLLAPSE or
                ImGuiWindowFlags.NO_SAVED_SETTINGS,
        )

        // ==================== Toolbar ====================
        ImGui.text("Language:")
        ImGui.sameLine()
        val names = Array(specs.size) { specs[it].displayName }
        if (ImGui.beginCombo("##language", names[current[0]])) {
            for (i in specs.indices) {
                if (ImGui.selectable(names[i], i == current[0])) {
                    select(i)
                }
            }
            ImGui.endCombo()
        }
        ImGui.textWrapped("(${specs.size} grammars — tree-sitter)")
        ImGui.text("Theme:")
        ImGui.sameLine()
        val themes = JetBrainsThemes.all
        if (ImGui.beginCombo("##theme", themes[themeIndex[0]].first)) {
            for (i in themes.indices) {
                if (ImGui.selectable(themes[i].first, i == themeIndex[0])) {
                    themeIndex[0] = i
                    editor.palette = themes[i].second
                }
            }
            ImGui.endCombo()
        }
        ImGui.textWrapped("(${themes.size} JetBrains themes)")

        // ==================== Editor ====================
        // Reserve the bottom status line so it never renders outside the
        // window (the editor fills the remaining space).
        val statusH = ImGui.getTextLineHeightWithSpacing() + 8f
        val avail = ImGui.getContentRegionAvail()
        editor.render("syntaxEditor", ImVec2(avail.x, (avail.y - statusH).coerceAtLeast(50f)))
        ImGui.separator()

        // ==================== Status bar ====================
        val spec = specs[current[0]]
        val pos = editor.cursor
        ImGui.text(
            "line ${pos.line + 1}, col ${pos.index + 1} — ${spec.displayName} — ${editor.getText().length} chars",
        )
        ImGui.end()
    }

    fun close() = Unit
}
