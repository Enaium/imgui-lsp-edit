package cn.enaium.lsp.edit.lsp

import cn.enaium.lsp.LanguageServer
import cn.enaium.lsp.LanguageServerLauncher
import cn.enaium.lsp.TextDocumentService
import cn.enaium.lsp.model.*
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.EditorFoldRange
import cn.enaium.lsp.edit.Language
import cn.enaium.lsp.edit.PaletteIndex
import cn.enaium.lsp.edit.TokenSpan
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavior tests for [LspEditor] against a real lsp-kmp server over the
 * in-memory transport: typing must trigger completion, and semantic tokens /
 * folding must refresh after edits (no stale results from superseded
 * requests).
 */
class LspEditorTest {

    /**
     * Server whose responses depend on the current document snapshot, so the
     * client's refresh results are verifiable per edit. Completion calls can
     * be counted and optionally gated (to hold a round trip in flight).
     */
    private class TestServer : LanguageServer {
        @Volatile
        var document: String = ""

        val completionCalls = AtomicInteger(0)
        val semanticCalls = AtomicLong(0)

        /** When non-null, completion handlers await it before answering. */
        @Volatile
        var completionGate: CompletableDeferred<Unit>? = null

        /** Milliseconds the folding handler sleeps before answering, only for the first call (simulates a briefly blocked read lock). */
        @Volatile
        var foldingDelayMs = 0L
        @Volatile
        private var foldingDelayed = false

        override fun initialize(params: InitializeParams): InitializeResult =
            InitializeResult(
                capabilities = ServerCapabilities(
                    textDocumentSync = TextDocumentSync.Kind(TextDocumentSyncKind.Incremental),
                    completionProvider = CompletionOptions(triggerCharacters = listOf(".")),
                    foldingRangeProvider = FoldingRangeProvider.Enabled(true),
                    semanticTokensProvider = SemanticTokensWithRegistrationOptions(
                        legend = SemanticTokensLegend(
                            tokenTypes = listOf("keyword", "string", "number"),
                            tokenModifiers = emptyList(),
                        ),
                    ),
                ),
                serverInfo = ServerInfo(name = "lsp-editor-test-server", version = "1.0.0"),
            )

        override fun shutdown(): Any? = null
        override fun exit() {}
        override fun workspaceService(): cn.enaium.lsp.WorkspaceService? = null
        override fun windowService(): cn.enaium.lsp.WindowService? = null

        override fun textDocumentService(): TextDocumentService = object : TextDocumentService {
            override fun didOpen(params: DidOpenTextDocumentParams) {
                document = params.textDocument.text
            }

            override fun didChange(params: DidChangeTextDocumentParams) {
                for (change in params.contentChanges) {
                    val range = change.range ?: continue
                    val text = document
                    val sb = StringBuilder(text)
                    sb.replace(
                        offsetOf(text, range.start),
                        offsetOf(text, range.end),
                        change.text,
                    )
                    document = sb.toString()
                }
            }

            private fun offsetOf(text: String, pos: Position): Int {
                var off = 0
                for (l in 0 until pos.line) {
                    val nl = text.indexOf('\n', off)
                    off = if (nl >= 0) nl + 1 else text.length
                }
                return (off + pos.character).coerceAtMost(text.length)
            }

            override fun completion(params: CompletionParams): CompletionResult {
                completionCalls.incrementAndGet()
                completionGate?.let { runBlocking { it.await() } }
                return CompletionResult.ListValue(
                    CompletionList(
                        items = listOf(
                            CompletionItem(label = "alpha", kind = CompletionItemKind.Variable),
                            CompletionItem(label = "beta", kind = CompletionItemKind.Function),
                        ),
                    ),
                )
            }

            override fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens {
                semanticCalls.incrementAndGet()
                // Newest document state: an 'x' prefix edits the first token.
                return if (document.startsWith("x")) {
                    SemanticTokens(data = listOf(0, 0, 1, 1)) // (0,0..1) "string"
                } else {
                    SemanticTokens(data = listOf(0, 0, 3, 0)) // (0,0..3) "keyword"
                }
            }

            override fun foldingRange(params: FoldingRangeRequestParams): List<FoldingRange> {
                if (foldingDelayMs > 0 && !foldingDelayed) {
                    foldingDelayed = true
                    Thread.sleep(foldingDelayMs)
                }
                // Longer documents (after an edit) fold a wider range.
                return listOf(
                    if (document.length > 13) {
                        FoldingRange(startLine = 0, endLine = 2)
                    } else {
                        FoldingRange(startLine = 0, endLine = 1)
                    },
                )
            }
        }
    }

    /**
     * Per-test rig with dedicated threads for the server listen loop, the
     * client listen loop and the LspEditor coroutines. The transports block
     * their thread while receiving, and other suites occupy the shared
     * `Dispatchers.Default` pool; dedicated threads keep these tests immune
     * to that contention.
     */
    private class TestRig : AutoCloseable {
        val pair = InMemoryTransportPair()
        val server = TestServer()
        private val serverThread = newSingleThreadContext("lsp-test-server")
        private val clientThread = newSingleThreadContext("lsp-test-client")
        private val workThread = newSingleThreadContext("lsp-test-work")
        private val serverScope = CoroutineScope(SupervisorJob() + serverThread)
        val clientScope = CoroutineScope(SupervisorJob() + clientThread)
        val workScope = CoroutineScope(SupervisorJob() + workThread)

        init {
            val launcher = LanguageServerLauncher(pair.b, server)
            serverScope.launch {
                launcher.listen()
            }
        }

        fun newLspEditor(text: String): LspEditor {
            val editor = Editor(initialText = text, language = Language.kotlin)
            return LspEditor(
                editor = editor,
                client = LspClient(pair.a, clientScope),
                uri = "file:///test.kt",
                languageId = "kotlin",
                scope = workScope,
            )
        }

        override fun close() {
            // Unblock the transport receives first, then release the threads.
            try { pair.close() } catch (_: Exception) {}
            try { serverScope.cancel() } catch (_: Exception) {}
            try { clientScope.cancel() } catch (_: Exception) {}
            try { workScope.cancel() } catch (_: Exception) {}
            try { serverThread.close() } catch (_: Exception) {}
            try { clientThread.close() } catch (_: Exception) {}
            try { workThread.close() } catch (_: Exception) {}
        }
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 5_000,
        condition: () -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return
            delay(50)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }

    @Test
    fun canceledRequestPropagatesCancellation() = runBlocking {
        val rig = TestRig()
        val gate = CompletableDeferred<Unit>()
        rig.server.completionGate = gate
        val client = LspClient(rig.pair.a, rig.clientScope)
        client.startListening()
        client.initialize(processId = 123)
        client.notifyInitialized()
        client.didOpen("file:///test.kt", "kotlin", 0, "fun main() {}")

        val job = launch(Dispatchers.Default) {
            client.completion("file:///test.kt", Position(0, 5))
        }
        // The request is in flight on the server when we cancel it.
        waitUntil { rig.server.completionCalls.get() == 1 }
        job.cancelAndJoin()
        // Cancellation must reach the caller: a superseded request may not
        // silently "succeed" (which would let a stale result overwrite a
        // newer refresh).
        assertTrue(job.isCancelled, "canceled request must propagate CancellationException")

        gate.complete(Unit)
        rig.close()
    }

    @Test
    fun typingTriggersCompletionAndNonIdentifierClosesIt() = runBlocking {
        val rig = TestRig()
        val lspEditor = rig.newLspEditor("fun main() {}")
        lspEditor.start(processId = 123)

        // Typing identifier characters must request completion.
        lspEditor.editor.inputText("x")
        waitUntil { lspEditor.completionActive }
        assertTrue(
            rig.server.completionCalls.get() >= 1,
            "typing must trigger a completion request",
        )

        // Further identifier characters re-request against the new prefix.
        lspEditor.editor.inputText("y")
        waitUntil { rig.server.completionCalls.get() >= 2 }
        assertTrue(lspEditor.completionActive, "popup stays open while typing an identifier")

        // A non-identifier character dismisses the popup.
        lspEditor.editor.inputText(" ")
        waitUntil { !lspEditor.completionActive }
        lspEditor.close()
        rig.close()
    }

    @Test
    fun completionItemTextShowsDetailAndDescription() {
        val rig = TestRig()
        val lspEditor = rig.newLspEditor("fun main() {}")

        // detail goes inline after the label.
        val withDetail = lspEditor.completionItemText(
            CompletionItem(label = "println", detail = "kotlin.io", kind = CompletionItemKind.Function),
        )
        assertEquals("println  —  kotlin.io", withDetail)

        // description (markup documentation) is appended as its own lines.
        val withDoc = lspEditor.completionItemText(
            CompletionItem(
                label = "print",
                detail = "kotlin.io",
                documentation = Documentation.Markup(
                    MarkupContent(MarkupKind.Markdown, "Prints a message to the standard output."),
                ),
            ),
        )
        assertEquals("print  —  kotlin.io\nPrints a message to the standard output.", withDoc)

        // labelDetails.detail is used when detail is absent.
        val withLabelDetails = lspEditor.completionItemText(
            CompletionItem(
                label = "foo",
                labelDetails = CompletionItemLabelDetails(detail = "the foo"),
            ),
        )
        assertEquals("foo  —  the foo", withLabelDetails)

        rig.close()
    }

    @Test
    fun slowFoldingResponseIsStillApplied() = runBlocking {
        val rig = TestRig()
        // The server answers folding only after a long block (like a server
        // whose read lock waits behind a didChange commit); the client must
        // keep the request alive and apply the answer whenever it arrives.
        rig.server.foldingDelayMs = 3_000
        val lspEditor = rig.newLspEditor("fun main() {}")
        lspEditor.start(processId = 123)

        waitUntil(8_000) {
            lspEditor.editor.getFoldRanges() == listOf(EditorFoldRange(0, 1))
        }
        lspEditor.editor.inputText("x")

        // The edit's response arrives after the server unblocks and is
        // applied (the folding refresh does not drop slow answers).
        waitUntil(15_000) {
            lspEditor.editor.getFoldRanges() == listOf(EditorFoldRange(0, 2))
        }

        lspEditor.close()
        rig.close()
    }

    @Test
    fun editsRefreshSemanticTokensAndFolding() = runBlocking {
        val rig = TestRig()
        val lspEditor = rig.newLspEditor("fun main() {}")
        lspEditor.start(processId = 123)

        // Edit the document; the server derives its answers from the new text.
        lspEditor.editor.inputText("x")

        // Semantic tokens reflect the edited document: the leading 'x' is a
        // "string" token, not the stale pre-edit "keyword".
        waitUntil {
            lspEditor.editor.spansOf(0) == listOf(TokenSpan(0, 1, PaletteIndex.STRING))
        }

        // Folding ranges reflect the edited (longer) document.
        waitUntil {
            lspEditor.editor.getFoldRanges() == listOf(EditorFoldRange(0, 2))
        }

        lspEditor.close()
        rig.close()
    }
}
