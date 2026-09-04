package cn.enaium.lsp.edit.lsp

import cn.enaium.lsp.jsonrpc.JsonRpcLauncher
import cn.enaium.lsp.jsonrpc.MessageTransport
import cn.enaium.lsp.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull

/**
 * An LSP client built on lsp-kmp's `JsonRpcLauncher`. Drives a single
 * connection to a language server over a [MessageTransport].
 *
 * Create with [connect], call [initialize] + [notifyInitialized] during the
 * handshake, then drive document notifications and requests. The client runs
 * a blocking receive loop on a background coroutine; dispatch thread safety
 * is the caller's responsibility (use [LspClientEventQueue] or marshal
 * callbacks manually).
 */
class LspClient(
    val transport: MessageTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    private val launcher = JsonRpcLauncher(transport)

    /**
     * Serializes all client→server requests: language servers (especially
     * IntelliJ-based ones) compute features under a read lock, and concurrent
     * requests wedge that lock — after a burst of edits every feature stops
     * answering. With one request in flight at a time the server is never
     * asked to do overlapping work.
     */
    private val requestMutex = Mutex()

    private var initialized = false
    private var serverCapabilities: ServerCapabilities? = null

    /** The server's identity, populated after [initialize]. */
    var serverInfo: ServerInfo? = null
        private set

    // ==================== Lifecycle ====================

    /** Sends the `initialize` request. Must be called first. */
    suspend fun initialize(
        processId: Int? = null,
        rootUri: String? = null,
        rootPath: String? = null,
        clientName: String = "lsp-edit",
        clientVersion: String? = null,
        capabilities: ClientCapabilities = defaultClientCapabilities(),
    ): InitializeResult {
        val params = InitializeParams(
            processId = processId,
            rootPath = rootPath,
            rootUri = rootUri,
            capabilities = capabilities,
            clientInfo = ClientInfo(clientName, clientVersion),
        )
        val result = launcher.request(
            "initialize",
            params,
            InitializeParams.serializer(),
            InitializeResult.serializer(),
        )
        serverCapabilities = result.capabilities
        serverInfo = result.serverInfo
        return result
    }

    /** Notifies the server that the client is initialized. */
    fun notifyInitialized() {
        launcher.notify("initialized", InitializedParams(), InitializedParams.serializer())
        initialized = true
    }

    /** Start the receive loop (blocks the calling coroutine/thread). */
    fun startListening() {
        scope.launch {
            launcher.listen()
        }
    }

    /** Sends `shutdown` request. Returns null result. */
    suspend fun shutdown() {
        try {
            withTimeout(2_000) {
                launcher.request("shutdown", JsonNull.serializer())
            }
        } catch (_: Exception) { /* server may not respond / timeout */ }
    }

    /** Sends `exit` notification (no params). */
    fun exit() {
        launcher.notify("exit", null)
    }

    override fun close() {
        if (initialized) {
            try {
                runBlocking { shutdown() }
            } catch (_: Exception) { /* ignore close failures */ }
            exit()
        }
    }

    // ==================== Notifications (server -> client) ====================

    /**
     * Registers a handler for `textDocument/publishDiagnostics`.
     * Called on the launcher's listen thread — marshal to the UI thread.
     */
    fun onPublishDiagnostics(handler: (PublishDiagnosticsParams) -> Unit) {
        launcher.onNotification(
            "textDocument/publishDiagnostics",
            PublishDiagnosticsParams.serializer(),
            handler,
        )
    }

    fun onShowMessage(handler: (MessageParams) -> Unit) {
        launcher.onNotification(
            "window/showMessage",
            MessageParams.serializer(),
            handler,
        )
    }

    fun onLogMessage(handler: (MessageParams) -> Unit) {
        launcher.onNotification(
            "window/logMessage",
            MessageParams.serializer(),
            handler,
        )
    }

    // ==================== Notifications (client -> server) ====================

    /** Opens a document in the server. Call after initialize. */
    fun didOpen(uri: String, languageId: String, version: Int, text: String) {
        val params = DidOpenTextDocumentParams(
            textDocument = TextDocumentItem(uri, languageId, version, text),
        )
        launcher.notify("textDocument/didOpen", params, DidOpenTextDocumentParams.serializer())
    }

    /** Sends incremental changes. Call after each edit. */
    fun didChange(
        uri: String,
        version: Int,
        contentChanges: List<TextDocumentContentChangeEvent>,
    ) {
        val params = DidChangeTextDocumentParams(
            textDocument = VersionedTextDocumentIdentifier(uri, version),
            contentChanges = contentChanges,
        )
        launcher.notify(
            "textDocument/didChange",
            params,
            DidChangeTextDocumentParams.serializer(),
        )
    }

    /** Closes the document. */
    fun didClose(uri: String) {
        val params = DidCloseTextDocumentParams(
            textDocument = TextDocumentIdentifier(uri),
        )
        launcher.notify("textDocument/didClose", params, DidCloseTextDocumentParams.serializer())
    }

    // ==================== Requests ====================

    /** Queries hover content at [position]; null = no hover. */
    suspend fun hover(uri: String, position: Position): Hover? =
        nullableRequest(
            "textDocument/hover",
            HoverParams(TextDocumentIdentifier(uri), position),
            HoverParams.serializer(),
            Hover.serializer(),
        )

    /** Queries completion items at [position]. */
    suspend fun completion(uri: String, position: Position): CompletionResult? =
        nullableRequest(
            "textDocument/completion",
            CompletionParams(TextDocumentIdentifier(uri), position),
            CompletionParams.serializer(),
            CompletionResult.serializer(),
        )

    /** Queries go-to-definition. Returns locations or location-links. */
    suspend fun definition(uri: String, position: Position): LocationResult? =
        nullableRequest(
            "textDocument/definition",
            DefinitionParams(TextDocumentIdentifier(uri), position),
            DefinitionParams.serializer(),
            LocationResult.serializer(),
        )

    /** Queries full-document semantic tokens. */
    suspend fun semanticTokensFull(uri: String): SemanticTokens? =
        nullableRequest(
            "textDocument/semanticTokens/full",
            SemanticTokensParams(textDocument = TextDocumentIdentifier(uri)),
            SemanticTokensParams.serializer(),
            SemanticTokens.serializer(),
        )

    /** Queries inlay hints within [range] (whole document by default). */
    suspend fun inlayHint(uri: String, range: Range): List<InlayHint>? =
        nullableRequest(
            "textDocument/inlayHint",
            InlayHintParams(textDocument = TextDocumentIdentifier(uri), range = range),
            InlayHintParams.serializer(),
            ListSerializer(InlayHint.serializer()),
        )

    /** Queries foldable line ranges for the document. */
    suspend fun foldingRange(uri: String): List<FoldingRange>? =
        nullableRequest(
            "textDocument/foldingRange",
            FoldingRangeRequestParams(textDocument = TextDocumentIdentifier(uri)),
            FoldingRangeRequestParams.serializer(),
            ListSerializer(FoldingRange.serializer()),
        )

    /** Requests formatting edits for the whole document (tabSize = 4, spaces). */
    suspend fun formatting(uri: String): List<TextEdit>? =
        nullableRequest(
            "textDocument/formatting",
            DocumentFormattingParams(
                textDocument = TextDocumentIdentifier(uri),
                options = FormattingOptions(tabSize = 4, insertSpaces = true),
            ),
            DocumentFormattingParams.serializer(),
            ListSerializer(TextEdit.serializer()),
        )

    /** The server's capabilities, populated after [initialize]. */
    fun getServerCapabilities(): ServerCapabilities? = serverCapabilities

    // ==================== Helpers ====================

    /**
     * Builds a default `ClientCapabilities` with text sync (incremental),
     * hover, completion, definition, semantic tokens.
     */
    companion object {
        fun defaultClientCapabilities(): ClientCapabilities = ClientCapabilities(
            textDocument = TextDocumentClientCapabilities(
                synchronization = SynchronizationCapabilities(
                    dynamicRegistration = false,
                    willSave = false,
                    willSaveWaitUntil = false,
                    didSave = false,
                ),
                completion = CompletionCapabilities(
                    completionItem = CompletionItemCapabilities(
                        snippetSupport = false,
                    ),
                ),
                hover = HoverCapabilities(
                    dynamicRegistration = false,
                    contentFormat = listOf(MarkupKind.PlainText, MarkupKind.Markdown),
                ),
                definition = DefinitionCapabilities(
                    dynamicRegistration = false,
                    linkSupport = true,
                ),
                inlayHint = InlayHintCapabilities(
                    dynamicRegistration = false,
                ),
                foldingRange = FoldingRangeCapabilities(
                    dynamicRegistration = false,
                    lineFoldingOnly = true,
                ),
                semanticTokens = SemanticTokensCapabilities(
                    requests = SemanticTokensClientCapabilitiesRequests(
                        full = BooleanOrDelta.Delta(true),
                        range = BooleanOrRaw.Enabled(true),
                    ),
                    tokenTypes = listOf(
                        "keyword", "variable", "function", "method", "class",
                        "namespace", "property", "parameter", "comment", "string",
                        "number", "operator", "type", "enum", "interface",
                    ),
                    tokenModifiers = listOf("declaration", "definition"),
                    formats = listOf("relative"),
                ),
            ),
        )

        /** Turns a [DocPos] into lsp-kmp [Position]. */
        fun toPosition(pos: cn.enaium.lsp.edit.DocPos): Position =
            Position(
                line = pos.line,
                character = pos.index,
            )

        /** Turns a lsp-kmp [Position] into [DocPos]. */
        fun toDocPos(pos: Position): cn.enaium.lsp.edit.DocPos =
            cn.enaium.lsp.edit.DocPos(
                line = pos.line,
                index = pos.character,
            )
    }

    // ==================== Internal ====================

    /**
     * Sends a typed request and returns null when the result is JsonNull.
     * [CancellationException] is rethrown: callers cancel superseded requests
     * (e.g. debounced refreshes) and must not let a canceled round trip
     * "succeed" with null and overwrite a newer result.
     */
    private suspend fun <T, R> nullableRequest(
        method: String,
        params: T,
        paramsSerializer: KSerializer<T>,
        resultSerializer: KSerializer<R>,
    ): R? {
        return try {
            requestMutex.withLock {
                // Bound the wait so a wedged server cannot stall the queue
                // forever; the caller re-issues on the next edit.
                withTimeout(30_000) {
                    launcher.request(method, params, paramsSerializer, resultSerializer)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // server returned null / error / timeout
        }
    }
}