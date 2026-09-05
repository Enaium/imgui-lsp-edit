package cn.enaium.lsp.edit.example

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.lsp.edit.DocPos
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.Language
import cn.enaium.lsp.edit.lsp.InMemoryTransportPair
import cn.enaium.lsp.edit.dap.DapClient
import cn.enaium.lsp.edit.dap.DapSession
import cn.enaium.lsp.edit.lsp.LspClient
import cn.enaium.lsp.edit.diff.DiffView
import cn.enaium.lsp.edit.lsp.LspEditor
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLWindowFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * The lsp-edit example: an ImGui window hosting an [LspEditor] connected to
 * the in-process [DemoLanguageServer] over an in-memory transport pair.
 *
 * - typing moves cursors and sends incremental didChange,
 * - hover a symbol for the server's hover documentation,
 * - type a keyword prefix and press Tab/Enter for completion,
 * - the gutter shows diagnostics pushed by the server,
 * - semantic tokens recolor keywords as you type.
 *
 * Run with `./gradlew :examples:editor:jvmRun` (JVM) or the native binaries;
 * pass `--frames N` / `IMGUI_KMP_FRAMES=N` to exit after N frames (headless
 * CI runs).
 */
fun runLspEditorExample(frames: Int = Int.MAX_VALUE, fallbackFontPath: String? = null) {
    var app: LspEditorApp? = null
    SdlRendererApp.run(
        title = "lsp-edit example",
        frames = frames,
        // 10px variant of the editor font for inlay hints.
        extraFontSizePx = 10f,
        // Merge a fallback font (e.g. a CJK font) so glyphs the main font
        // lacks still render; pass null to disable.
        fallbackFontPath = fallbackFontPath,
        init = { extraFont ->
            app = LspEditorApp().apply { editor.inlayHintFont = extraFont }
        },
        draw = { frame -> app?.draw(frame) },
        close = { app?.close() },
        // Route SDL text input into the focused editor (the Kotlin IO
        // binding exposes no input-queue reader; the widget consumes
        // characters pushed by the host).
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

/** The demo UI: toolbar, editor, status bar. */
private class LspEditorApp {

    val editor = Editor(
        initialText = SAMPLE_KOTLIN,
        language = Language.kotlin,
    )

    private val pair = InMemoryTransportPair()
    private val client = LspClient(pair.a)
    val lspEditor = LspEditor(editor, client, uri = "file:///demo.kt", languageId = "kotlin")

    /** Side-by-side diff of SAMPLE_KOTLIN vs a modified variant. */
    val diffView = DiffView(SAMPLE_KOTLIN, SAMPLE_KOTLIN_MODIFIED)
    private val showDiff = BooleanArray(1)
    private val showMinimap = BooleanArray(1) { true }

    private val debugPair = InMemoryTransportPair()
    private val debugClient = DapClient(debugPair.a)
    val debugAdapter = DemoDebugAdapter().apply { sourcePath = "file:///demo.kt" }
    val debugSession = DapSession(
        editor = editor,
        client = debugClient,
        sourceName = "demo.kt",
        sourcePath = "file:///demo.kt",
    )

    private val showLineNumbers = BooleanArray(1) { true }
    private val wrap = BooleanArray(1) { false }
    private val darkPalette = BooleanArray(1) { true }
    private val showIndentGuides = BooleanArray(1) { false }
    private var status = "connecting…"
    private var diagnosticsCount = 0

    init {
        runDemoServer(pair, DemoLanguageServer())
        runDemoDebugAdapter(debugPair, debugAdapter)
        GlobalScope.launch(Dispatchers.Default) {
            lspEditor.start(rootUri = "file:///")
            status = "connected to lsp-edit-demo"
        }
        lspEditor.onShowMessage = { status = it }
        lspEditor.onLogMessage = { status = it }

        // Code lens above main(): a clickable "Run" that starts the demo
        // debug session, VS Code style.
        val mainLine = editor.buffer.lineCount().let { total ->
            (0 until total).firstOrNull { editor.buffer.line(it).startsWith("fun main") } ?: 0
        }
        editor.codeLensProvider = { line ->
            if (line == mainLine) {
                listOf(
                    cn.enaium.lsp.edit.EditorCodeLens(line, "Run (F5)", "demo.run"),
                    cn.enaium.lsp.edit.EditorCodeLens(line, "▶ main", null),
                )
            } else null
        }
        editor.onCodeLensClick = { lens ->
            status = "code lens: ${lens.title}${lens.command?.let { " ($it)" } ?: ""}"
            if (lens.command == "demo.run" && !debugSession.active) {
                GlobalScope.launch(Dispatchers.Default) {
                    debugSession.start(breakpoints = editor.getBreakpointLines())
                }
            }
        }

        // F9 in the editor toggles breakpoints; keep the debug session in
        // sync with the adapter.
        editor.onBreakpointsChange = { lines ->
            GlobalScope.launch(Dispatchers.Default) {
                debugSession.applyBreakpoints(lines)
            }
        }
        debugSession.wireEvents()
        debugSession.onStopped { pos ->
            if (pos != null) editor.setCursor(pos)
        }
    }

    fun draw(frame: Int) {
        // Apply frame-pending server state (diagnostics markers, messages).
        lspEditor.drain()
        debugSession.drain()

        // Fullscreen editor: fill the whole window, no decorations.
        val display = ImGui.getIO().displaySize
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(display, ImGuiCond.ALWAYS)
        ImGui.begin(
            "LSP Editor (lsp-edit)",
            null,
            cn.enaium.imgui.ImGuiWindowFlags.NO_MOVE or
                cn.enaium.imgui.ImGuiWindowFlags.NO_RESIZE or
                cn.enaium.imgui.ImGuiWindowFlags.NO_TITLE_BAR or
                cn.enaium.imgui.ImGuiWindowFlags.NO_COLLAPSE or
                cn.enaium.imgui.ImGuiWindowFlags.NO_SAVED_SETTINGS,
        )

        // ==================== Toolbar ====================
        if (ImGui.checkbox("Line numbers", showLineNumbers)) {
            editor.showLineNumbers = showLineNumbers[0]
        }
        ImGui.sameLine()
        if (ImGui.checkbox("Wrap", wrap)) {
            editor.wrapEnabled = wrap[0]
        }
        ImGui.sameLine()
        if (ImGui.checkbox("Dark palette", darkPalette)) {
            if (darkPalette[0]) editor.palette = cn.enaium.lsp.edit.EditorPalette.dark
        }
        ImGui.sameLine()
        if (ImGui.checkbox("Indent guides", showIndentGuides)) {
            editor.showIndentGuides = showIndentGuides[0]
        }
        ImGui.sameLine()
        if (ImGui.button("Undo")) editor.undo()
        ImGui.sameLine()
        if (ImGui.button("Redo")) editor.redo()
        ImGui.sameLine()
        if (ImGui.button("Go to definition (F12)")) lspEditor.gotoDefinition()
        ImGui.sameLine()
        if (ImGui.button("Completion (Ctrl+Space)")) lspEditor.requestCompletion()
        ImGui.sameLine()
        if (ImGui.checkbox("Diff", showDiff)) Unit
        ImGui.sameLine()
        if (ImGui.checkbox("Minimap", showMinimap)) {
            editor.showMinimap = showMinimap[0]
        }

        ImGui.separatorText("Cursor")
        val cursorW = FloatArray(1) { editor.cursorWidthChars }
        if (ImGui.sliderFloat("Width (chars)", cursorW, 0.05f, 1f, "%.2f")) {
            editor.cursorWidthChars = cursorW[0]
        }
        ImGui.sameLine()
        val cursorH = FloatArray(1) { editor.cursorHeightLines }
        if (ImGui.sliderFloat("Height (lines)", cursorH, 0.1f, 1f, "%.2f")) {
            editor.cursorHeightLines = cursorH[0]
        }

        ImGui.separator()

        // ==================== Editor ====================
        // Reserve the bottom panel (debug + status) so the editor never
        // pushes it outside the window: measure the panel height first by
        // rendering it into a hidden-sized probe? No — compute a fixed
        // budget: debug buttons + call stack + variables + output can grow,
        // so the bottom panel gets its own scrollable child with a capped
        // height, and the editor fills everything above it.
        val bottomBudget = 260f
        val avail = ImGui.getContentRegionAvail()
        val editorH = (avail.y - bottomBudget).coerceAtLeast(120f)
        editor.overlay = { lspEditor.renderCompletionPopup() }
        editor.render("##editor", ImVec2(avail.x, editorH))

        // Hover tooltip drawn outside the child window.
        lspEditor.renderHoverTooltip()

        // ==================== Bottom panel (debug + status) ====================
        if (ImGui.beginChild("##bottom", ImVec2(avail.x, bottomBudget), 0,
                cn.enaium.imgui.ImGuiWindowFlags.HORIZONTAL_SCROLLBAR)) {
        // ==================== Debug panel ====================
        ImGui.separatorText("Debug (DAP)")
        if (ImGui.button("Start") && !debugSession.active) {
            GlobalScope.launch(Dispatchers.Default) {
                debugSession.start(breakpoints = editor.getBreakpointLines())
            }
        }
        ImGui.sameLine()
        if (ImGui.button("Continue") && debugSession.active && !debugSession.running) {
            GlobalScope.launch(Dispatchers.Default) { debugSession.continue_() }
        }
        ImGui.sameLine()
        if (ImGui.button("Step") && debugSession.active && !debugSession.running) {
            GlobalScope.launch(Dispatchers.Default) { debugSession.next() }
        }
        ImGui.sameLine()
        if (ImGui.button("Step In") && debugSession.active && !debugSession.running) {
            GlobalScope.launch(Dispatchers.Default) { debugSession.stepIn() }
        }
        ImGui.sameLine()
        if (ImGui.button("Step Out") && debugSession.active && !debugSession.running) {
            GlobalScope.launch(Dispatchers.Default) { debugSession.stepOut() }
        }
        ImGui.sameLine()
        if (ImGui.button("Stop") && debugSession.active) {
            GlobalScope.launch(Dispatchers.Default) { debugSession.stop() }
        }
        ImGui.sameLine()
        ImGui.text(
            if (debugSession.active) {
                if (debugSession.running) "running" else "stopped (${debugSession.stopReason})"
            } else {
                "idle — F9 toggles breakpoints, then Start"
            },
        )

        // Call stack.
        if (debugSession.stackFrames.isNotEmpty()) {
            ImGui.separatorText("Call stack")
            for ((i, frame) in debugSession.stackFrames.withIndex()) {
                val label = "${frame.name} — line ${frame.line}"
                if (ImGui.selectable(label, i == debugSession.selectedFrame)) {
                    debugSession.selectFrame(i)
                }
            }
        }

        // Scopes + variables of the selected frame.
        if (debugSession.variables.isNotEmpty()) {
            ImGui.separatorText("Variables (${debugSession.scopes.firstOrNull()?.name ?: "Locals"})")
            for (v in debugSession.variables) {
                val ref = v.variablesReference
                val label = if (ref != 0) "${v.name}: ${v.value}  [+]" else "${v.name}: ${v.value}"
                ImGui.text(label)
            }
        }

        // Adapter output.
        if (debugSession.output.isNotEmpty()) {
            ImGui.separatorText("Output")
            for (lineText in debugSession.output.takeLast(8)) {
                ImGui.textWrapped(lineText)
            }
        }

        // ==================== Status bar ====================
        ImGui.separator()
        val (line, index) = editor.cursor
        val lines = editor.lineCount()
        ImGui.text(
            "status: $status   |   Ln $line, Col $index   |   ${lines} lines   |   " +
                "diagnostics: $diagnosticsCount   |   version: ${lspEditor.version}   |   frame: $frame",
        )
        if (ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.F12)) {
            lspEditor.gotoDefinition()
        }
        if (cn.enaium.imgui.ImGui.isKeyDown(cn.enaium.imgui.ImGuiKey.MOD_CTRL) &&
            cn.enaium.imgui.ImGui.isKeyPressed(cn.enaium.imgui.ImGuiKey.SPACE)
        ) {
            lspEditor.requestCompletion()
        }
        ImGui.endChild()
        } // end bottom child
        ImGui.end()

        // ==================== Diff view ====================
        // Rendered as its own top-level window AFTER the fullscreen editor
        // window closes: beginning a window inside another window's
        // begin/end can leave it clipped or hidden behind the fullscreen
        // parent, so the diff never shows.
        if (showDiff[0]) {
            ImGui.setNextWindowPos(ImVec2(80f, 60f), ImGuiCond.FIRST_USE_EVER)
            ImGui.setNextWindowSize(ImVec2(900f, 500f), ImGuiCond.FIRST_USE_EVER)
            ImGui.begin("Diff (old -> new)")
            diffView.render("##diff")
            ImGui.end()
        }
    }

    fun close() {
        GlobalScope.launch(Dispatchers.Default) { debugSession.stop() }
        debugSession.close()
        debugClient.close()
        debugPair.close()
        lspEditor.close()
        pair.close()
    }

    companion object {
        val SAMPLE_KOTLIN_MODIFIED = """
            |// A small Kotlin sample for the lsp-edit demo.
            |// 中文注释：依赖回退字体渲染（fallback font）。
            |package demo
            |
            |import kotlin.math.sqrt
            |
            |data class Point(val x: Double, val y: Double) {
            |    fun length() = sqrt(x * x + y * y)
            |}
            |
            |fun distance(a: Point, b: Point): Double {
            |    val dx = a.x - b.x
            |    val dy = a.y - b.y
            |    return sqrt(dx * dx + dy * dy)
            |}
            |
            |fun main() {
            |    val origin = Point(0.0, 0.0)
            |    val target = Point(3.0, 4.0)
            |    println("distance = ${'$'}{distance(origin, target)}")
            |    println("length = ${'$'}{target.length()}")
            |}
            |""".trimMargin()

        val SAMPLE_KOTLIN = """
            |// A small Kotlin sample for the lsp-edit demo.
            |// 中文注释：依赖回退字体渲染（fallback font）。
            |package demo
            |
            |import kotlin.math.sqrt
            |
            |data class Point(val x: Double, val y: Double)
            |
            |fun distance(a: Point, b: Point): Double {
            |    val dx = a.x - b.x
            |    val dy = a.y - b.y
            |    return sqrt(dx * dx + dy * dy)
            |}
            |
            |fun main() {
            |    val origin = Point(0.0, 0.0)
            |    val target = Point(3.0, 4.0)
            |    println("distance = ${'$'}{distance(origin, target)}")
            |}
            |""".trimMargin()
    }
}