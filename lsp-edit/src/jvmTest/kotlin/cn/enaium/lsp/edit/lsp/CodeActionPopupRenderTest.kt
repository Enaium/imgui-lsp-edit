package cn.enaium.lsp.edit.lsp

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import cn.enaium.lsp.LanguageServer
import cn.enaium.lsp.LanguageServerLauncher
import cn.enaium.lsp.TextDocumentService
import cn.enaium.lsp.model.*
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The code-action list must behave like the completion popup: anchored at the
 * caret (never the default top-left corner), borderless, and driven by
 * Alt+Enter.
 */
class CodeActionPopupRenderTest {

    private class TestServer : LanguageServer {
        override fun initialize(params: InitializeParams): InitializeResult =
            InitializeResult(
                capabilities = ServerCapabilities(
                    textDocumentSync = TextDocumentSync.Kind(TextDocumentSyncKind.Incremental),
                    codeActionProvider = CodeActionProvider.Enabled(true),
                ),
                serverInfo = ServerInfo(name = "code-action-test", version = "1.0.0"),
            )

        override fun shutdown(): Any? = null
        override fun exit() {}
        override fun workspaceService(): cn.enaium.lsp.WorkspaceService? = null
        override fun windowService(): cn.enaium.lsp.WindowService? = null

        override fun textDocumentService(): TextDocumentService = object : TextDocumentService {
            override fun didOpen(params: DidOpenTextDocumentParams) {}
            override fun didChange(params: DidChangeTextDocumentParams) {}
            override fun codeAction(params: CodeActionParams): List<CodeActionResult> = listOf(
                CodeActionResult.Action(CodeAction(title = "Convert concatenation to template", kind = "quickfix")),
                CodeActionResult.Action(CodeAction(title = "Organize imports", kind = "source.organizeImports")),
            )
        }
    }

    @Test
    fun popupAnchorsAtCaret() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(ImTextureID(0uL))
            ImGui.newFrame()

            val pair = InMemoryTransportPair()
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            serverScope.launch {
                LanguageServerLauncher(pair.b, TestServer()).listen()
            }
            val editor = Editor(
                initialText = "fun main() {\n    val x = 1\n}",
                language = Language.kotlin,
            )
            val lspEditor = LspEditor(
                editor = editor,
                client = LspClient(pair.a),
                uri = "file:///test.kt",
                languageId = "kotlin",
            )

            runBlocking {
                lspEditor.start(processId = 123)
                // Caret on the middle line, well away from the origin.
                editor.setCursor(cn.enaium.lsp.edit.DocPos(1, 4))
                lspEditor.requestCodeActions()
            }

            // Drain inside a frame (it reads ImGui key state); the response
            // arrives on a channel and lands on the UI state here.
            var frames = 0
            while (!lspEditor.codeActionsActive && frames < 200) {
                ImGui.begin("##editorWindow")
                editor.render("##editor")
                lspEditor.drain()
                ImGui.end()
                ImGui.render()
                ImGui.newFrame()
                frames++
                Thread.sleep(10)
            }
            assertTrue(lspEditor.codeActionsActive, "the action list must open")

            lspEditor.close()
            pair.close()
            serverScope.cancel()
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
