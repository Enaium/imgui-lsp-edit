package cn.enaium.lsp.edit.lsp

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
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
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Smoke test for the completion popup: a real LSP round trip populates the
 * list, and rendering the popup headlessly must not crash and must produce
 * draw lists (multi-line items with detail + description included).
 */
class CompletionPopupRenderTest {

    private class TestServer : LanguageServer {
        override fun initialize(params: InitializeParams): InitializeResult =
            InitializeResult(
                capabilities = ServerCapabilities(
                    textDocumentSync = TextDocumentSync.Kind(TextDocumentSyncKind.Incremental),
                    completionProvider = CompletionOptions(triggerCharacters = listOf(".")),
                ),
                serverInfo = ServerInfo(name = "popup-test", version = "1.0.0"),
            )

        override fun shutdown(): Any? = null
        override fun exit() {}
        override fun workspaceService(): cn.enaium.lsp.WorkspaceService? = null
        override fun windowService(): cn.enaium.lsp.WindowService? = null

        val completionCalls = java.util.concurrent.atomic.AtomicInteger(0)

        override fun textDocumentService(): TextDocumentService = object : TextDocumentService {
            override fun didOpen(params: DidOpenTextDocumentParams) {}
            override fun didChange(params: DidChangeTextDocumentParams) {}
            override fun completion(params: CompletionParams): CompletionResult {
                completionCalls.incrementAndGet()
                return CompletionResult.ListValue(
                    CompletionList(
                        items = listOf(
                            CompletionItem(
                                label = "println",
                                detail = "kotlin.io",
                                kind = CompletionItemKind.Function,
                                documentation = Documentation.Markup(
                                    MarkupContent(
                                        MarkupKind.Markdown,
                                        "Prints a message and a newline to the standard output stream.",
                                    ),
                                ),
                            ),
                            CompletionItem(
                                label = "print",
                                detail = "kotlin.io",
                                documentation = Documentation.StringValue("Prints a message to the standard output stream."),
                            ),
                            CompletionItem(label = "printf", detail = "libc"),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun rendersPopupWithDetailAndDescription() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)
            ImGui.newFrame()

            val pair = InMemoryTransportPair()
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            serverScope.launch {
                LanguageServerLauncher(pair.b, TestServer()).listen()
            }
            val editor = Editor(initialText = "fun main() {\n    val x = pri\n}", language = Language.kotlin)
            val lspEditor = LspEditor(
                editor = editor,
                client = LspClient(pair.a),
                uri = "file:///test.kt",
                languageId = "kotlin",
            )

            runBlocking {
                lspEditor.start(processId = 123)
                // Put the caret at the end of "pri" and type an identifier
                // character to trigger completion.
                editor.setCursor(cn.enaium.lsp.edit.DocPos(1, 14))
                editor.inputText("n")
                val deadline = System.currentTimeMillis() + 5_000
                while (!lspEditor.completionActive && System.currentTimeMillis() < deadline) {
                    delay(20)
                }
            }
            assertTrue(lspEditor.completionActive, "typing must open the completion popup")

            // Render the editor + popup for two frames (first frame warms up).
            ImGui.begin("##editorWindow")
            editor.render("##editor")
            lspEditor.renderCompletionPopup()
            ImGui.end()
            ImGui.render()
            ImGui.newFrame()

            ImGui.begin("##editorWindow")
            editor.render("##editor")
            lspEditor.renderCompletionPopup()
            ImGui.end()
            ImGui.render()

            val dd = ImGui.getDrawData()
            assertTrue(dd.cmdListsCount > 0, "completion popup produced no draw lists")

            lspEditor.close()
            pair.close()
            serverScope.cancel()
        } finally {
            ImGui.destroyContext(ctx)
        }
    }

    @Test
    fun acceptingCompletionDoesNotRetrigger() {
        val ctx = ImGui.createContext()
        try {
            val io = ImGui.getIO()
            io.displaySize = ImVec2(1280f, 800f)
            io.deltaTime = 1f / 60f
            io.fonts.addFontDefault(ImFontConfig(sizePixels = 13f))
            check(io.fonts.build()) { "font build failed" }
            io.fonts.setTexID(0)
            ImGui.newFrame()

            val pair = InMemoryTransportPair()
            val server = TestServer()
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            serverScope.launch {
                LanguageServerLauncher(pair.b, server).listen()
            }
            val editor = Editor(initialText = "fun main() {\n    val x = pri\n}", language = Language.kotlin)
            val lspEditor = LspEditor(
                editor = editor,
                client = LspClient(pair.a),
                uri = "file:///test.kt",
                languageId = "kotlin",
            )

            runBlocking {
                lspEditor.start(processId = 123)
                editor.setCursor(cn.enaium.lsp.edit.DocPos(1, 14))
                editor.inputText("n")
                val deadline = System.currentTimeMillis() + 5_000
                while (!lspEditor.completionActive && System.currentTimeMillis() < deadline) {
                    delay(20)
                }
                assertTrue(lspEditor.completionActive, "completion popup must open")
                val callsBefore = server.completionCalls.get()

                // Accept with Enter: the popup must close and the acceptance
                // edit must NOT re-request completion.
                io.addKeyEvent(cn.enaium.imgui.ImGuiKey.ENTER, true)
                io.addKeyEvent(cn.enaium.imgui.ImGuiKey.ENTER, false)
                ImGui.newFrame()
                ImGui.begin("##win")
                editor.render("##editor")
                lspEditor.renderCompletionPopup()
                ImGui.end()
                ImGui.render()

                assertFalse(lspEditor.completionActive, "accepted completion must close the popup")
                assertTrue(
                    editor.getText().contains("val x = println"),
                    "accepted item must be inserted, got: ${editor.getText()}",
                )
                delay(500)
                assertTrue(
                    server.completionCalls.get() == callsBefore,
                    "acceptance edit must not re-trigger completion: " +
                        "${server.completionCalls.get()} vs $callsBefore",
                )
                assertFalse(lspEditor.completionActive, "popup must stay closed after the accept edit")

                lspEditor.close()
            }
            pair.close()
            serverScope.cancel()
        } finally {
            ImGui.destroyContext(ctx)
        }
    }
}
