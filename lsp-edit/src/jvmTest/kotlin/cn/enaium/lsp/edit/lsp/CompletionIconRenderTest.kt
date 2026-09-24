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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The completion popup draws an icon per item kind, into the gutter its label
 * leaves free. Two things can silently break: the mapping (a kind that loses
 * its icon renders blank) and the render path itself (drawing into the popup's
 * draw list before the window is live).
 */
class CompletionIconRenderTest {

    private class TestServer : LanguageServer {
        override fun initialize(params: InitializeParams): InitializeResult =
            InitializeResult(
                capabilities = ServerCapabilities(
                    textDocumentSync = TextDocumentSync.Kind(TextDocumentSyncKind.Incremental),
                    completionProvider = CompletionOptions(),
                ),
                serverInfo = ServerInfo(name = "completion-test", version = "1.0.0"),
            )

        override fun shutdown(): Any? = null
        override fun exit() {}
        override fun workspaceService(): cn.enaium.lsp.WorkspaceService? = null
        override fun windowService(): cn.enaium.lsp.WindowService? = null

        override fun textDocumentService(): TextDocumentService = object : TextDocumentService {
            override fun didOpen(params: DidOpenTextDocumentParams) {}
            override fun didChange(params: DidChangeTextDocumentParams) {}
            override fun completion(params: CompletionParams): CompletionResult =
                CompletionResult.ListValue(
                    CompletionList(
                        isIncomplete = false,
                        items = listOf(
                            CompletionItem(label = "toString", kind = CompletionItemKind.Method),
                            CompletionItem(label = "size", kind = CompletionItemKind.Property),
                            CompletionItem(label = "count", kind = CompletionItemKind.Variable),
                            CompletionItem(label = "keyword", kind = CompletionItemKind.Keyword),
                        ),
                    ),
                )
        }
    }

    /** Kinds the popup renders an icon for; the rest fall back to plain text. */
    @Test
    fun kindsMapToIcons() {
        for (kind in listOf(
            CompletionItemKind.Method,
            CompletionItemKind.Function,
            CompletionItemKind.Constructor,
            CompletionItemKind.Field,
            CompletionItemKind.Variable,
            CompletionItemKind.Class,
            CompletionItemKind.Interface,
            CompletionItemKind.Module,
            CompletionItemKind.Property,
            CompletionItemKind.Enum,
            CompletionItemKind.Constant,
            CompletionItemKind.Folder,
        )) {
            assertNotNull(CompletionIcons.iconFor(kind), "kind $kind must have an icon")
        }
    }

    @Test
    fun popupRendersWithIcons() {
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
            serverScope.launch { LanguageServerLauncher(pair.b, TestServer()).listen() }
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
                editor.setCursor(cn.enaium.lsp.edit.DocPos(1, 4))
                lspEditor.requestCompletion()
            }

            // The popup draws through the editor's overlay; render a window
            // with it so the icon draws land in a live draw list.
            var frames = 0
            while (!lspEditor.completionActive && frames < 200) {
                renderFrame(editor, lspEditor)
                frames++
                Thread.sleep(10)
            }
            assertTrue(lspEditor.completionActive, "the completion popup must open")

            // A few more frames with the popup visibly drawing its icons.
            repeat(3) {
                renderFrame(editor, lspEditor)
                Thread.sleep(10)
            }

            lspEditor.close()
            pair.close()
            serverScope.cancel()
        } finally {
            ImGui.destroyContext(ctx)
        }
    }

    private fun renderFrame(editor: Editor, lspEditor: LspEditor) {
        ImGui.begin("##editorWindow")
        editor.render("##editor")
        lspEditor.drain()
        lspEditor.renderCompletionPopup()
        ImGui.end()
        ImGui.render()
        ImGui.newFrame()
    }
}
