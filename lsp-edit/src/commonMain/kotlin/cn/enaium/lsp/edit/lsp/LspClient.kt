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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
        workspaceFolders: List<WorkspaceFolder>? = null,
        trace: String? = null,
    ): InitializeResult {
        val params = InitializeParams(
            processId = processId,
            rootPath = rootPath,
            rootUri = rootUri,
            workspaceFolders = workspaceFolders,
            capabilities = capabilities,
            clientInfo = ClientInfo(clientName, clientVersion),
            trace = trace,
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

    /**
     * Server-initiated workspace edits (`workspace/applyEdit`): code-action
     * commands, organize-imports, ... The handler returns whether the edit was
     * applied. Without one the server is told `applied = false`.
     */
    private val applyEditHandlers = mutableListOf<(WorkspaceEdit) -> Boolean>()

    /**
     * Registers a `workspace/applyEdit` handler; the first one that returns
     * true wins. Handlers stack instead of replacing each other, so a host
     * and an [cn.enaium.lsp.edit.lsp.LspEditor] bound to the same client both
     * get their say.
     */
    fun addApplyEditHandler(handler: (WorkspaceEdit) -> Boolean) {
        applyEditHandlers.add(handler)
    }

    /** Convenience for hosts that only ever register one handler. */
    var onApplyEdit: ((WorkspaceEdit) -> Boolean)?
        get() = applyEditHandlers.firstOrNull()
        set(value) {
            applyEditHandlers.clear()
            if (value != null) applyEditHandlers.add(value)
        }

    /**
     * Start the receive loop (blocks the calling coroutine/thread). Registers
     * the server-initiated requests first: a server that gets "method not
     * found" for `workspace/configuration` refuses to serve features such as
     * inlay hints.
     */
    fun startListening() {
        registerServerRequests()
        scope.launch {
            launcher.listen()
        }
    }

    private fun registerServerRequests() {
        launcher.onRequestJson("workspace/applyEdit", ApplyWorkspaceEditParams.serializer()) { params ->
            val applied = applyEditHandlers.any { it(params.edit) }
            kotlinx.serialization.json.buildJsonObject {
                put("applied", kotlinx.serialization.json.JsonPrimitive(applied))
            }
        }
        // null per requested section: the server keeps its defaults.
        launcher.onRequestJson("workspace/configuration", JsonElement.serializer()) { params ->
            val count = (params as? kotlinx.serialization.json.JsonObject)
                ?.get("items")?.let { it as? kotlinx.serialization.json.JsonArray }?.size ?: 0
            kotlinx.serialization.json.JsonArray(List(count) { kotlinx.serialization.json.JsonNull })
        }
        // Acknowledging is all the client must do.
        launcher.onRequestJson("window/workDoneProgress/create", JsonElement.serializer()) { _ ->
            kotlinx.serialization.json.JsonNull
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

    /** `\$/progress` work-done notifications (server-side task progress). */
    fun onProgress(handler: (ProgressParams) -> Unit) {
        launcher.onNotification(
            "\$/progress",
            ProgressParams.serializer(),
            handler,
        )
    }

    /** Any other server notification, by method name (vendor extensions). */
    fun onCustomNotification(method: String, handler: (JsonElement) -> Unit) {
        launcher.onNotification(method, JsonElement.serializer(), handler)
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
    /** Saves a document (optionally including its text). */
    fun didSave(uri: String, text: String? = null) {
        val params = DidSaveTextDocumentParams(
            textDocument = TextDocumentIdentifier(uri),
            text = text,
        )
        launcher.notify("textDocument/didSave", params, DidSaveTextDocumentParams.serializer())
    }

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

    // ==================== Symbol structure ====================

    /**
     * Queries the document symbol tree (`textDocument/documentSymbol`).
     *
     * The result is either a flat list of [SymbolInformation] (flat mode) or
     * a nested list of [DocumentSymbol] (hierarchical mode). lsp-kmp's
     * DocumentSymbolResult serializer mishandles the array form, so the raw
     * JsonElement is decoded here with the list serializers directly.
     */
    suspend fun documentSymbols(uri: String): List<DocumentSymbol>? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "textDocument/documentSymbol",
                        DocumentSymbolParams(textDocument = TextDocumentIdentifier(uri)),
                        DocumentSymbolParams.serializer(),
                        JsonElement.serializer(),
                    )
                }
            }?.let { element ->
                val arr = element as? kotlinx.serialization.json.JsonArray ?: return null
                // Hierarchical mode: objects carry "selectionRange".
                if (arr.any { (it as? kotlinx.serialization.json.JsonObject)?.containsKey("selectionRange") == true }) {
                    LspJson.json.decodeFromJsonElement(
                        ListSerializer(DocumentSymbol.serializer()),
                        element,
                    )
                } else {
                    // Flat mode: a flat server lists every symbol as a sibling,
                    // with the container only named. Nest it into the same tree
                    // shape the hierarchical form already has.
                    val flat = LspJson.json.decodeFromJsonElement(
                        ListSerializer(SymbolInformation.serializer()),
                        element,
                    )
                    buildSymbolTree(flat)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Queries code lenses for the document. */
    suspend fun codeLens(uri: String): List<CodeLens>? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "textDocument/codeLens",
                        CodeLensParams(textDocument = TextDocumentIdentifier(uri)),
                        CodeLensParams.serializer(),
                        ListSerializer(CodeLens.serializer()),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Resolves a code lens (fills its command). */
    suspend fun codeLensResolve(lens: CodeLens): CodeLens? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "codeLens/resolve",
                        lens,
                        CodeLens.serializer(),
                        CodeLens.serializer(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    // ==================== Symbol search ====================

    /** Queries workspace symbols matching [query]. */
    suspend fun workspaceSymbols(query: String): List<WorkspaceSymbol>? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "workspace/symbol",
                        WorkspaceSymbolParams(query = query),
                        WorkspaceSymbolParams.serializer(),
                        WorkspaceSymbolResult.serializer(),
                    )
                }
            }?.let { result ->
                when (result) {
                    is WorkspaceSymbolResult.Symbols -> result.value
                    is WorkspaceSymbolResult.SymbolInfos -> result.value.map {
                        WorkspaceSymbol(
                            name = it.name,
                            kind = it.kind,
                            location = SymbolLocation.LocationValue(it.location),
                            containerName = it.containerName,
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    // ==================== References / rename ====================

    /** Queries all references to the symbol at [position]. */
    suspend fun references(uri: String, position: Position): List<Location>? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "textDocument/references",
                        ReferenceParams(
                            textDocument = TextDocumentIdentifier(uri),
                            position = position,
                            context = ReferenceContext(includeDeclaration = true),
                        ),
                        ReferenceParams.serializer(),
                        ListSerializer(Location.serializer()),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Fills in a completion item (additionalTextEdits, documentation). */
    suspend fun completionResolve(item: CompletionItem): CompletionItem? =
        try {
            requestMutex.withLock {
                withTimeout(15_000) {
                    launcher.request(
                        "completionItem/resolve",
                        item,
                        CompletionItem.serializer(),
                        CompletionItem.serializer(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Code actions (quick fixes, refactors) available over [range]. */
    suspend fun codeAction(
        uri: String,
        range: Range,
        diagnostics: List<Diagnostic>,
    ): List<CodeAction>? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "textDocument/codeAction",
                        CodeActionParams(
                            textDocument = TextDocumentIdentifier(uri),
                            range = range,
                            context = CodeActionContext(diagnostics = diagnostics),
                        ),
                        CodeActionParams.serializer(),
                        ListSerializer(CodeAction.serializer()),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Fills in a lazily-resolved code action (its edit/command). */
    suspend fun codeActionResolve(action: CodeAction): CodeAction? =
        try {
            requestMutex.withLock {
                withTimeout(15_000) {
                    launcher.request(
                        "codeAction/resolve",
                        action,
                        CodeAction.serializer(),
                        CodeAction.serializer(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Runs a server command; the server's edits arrive as workspace/applyEdit. */
    suspend fun executeCommand(command: String, arguments: List<JsonElement>?): Boolean =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "workspace/executeCommand",
                        ExecuteCommandParams(command = command, arguments = arguments),
                        ExecuteCommandParams.serializer(),
                        JsonElement.serializer(),
                    )
                }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    /** Checks whether the symbol at [position] can be renamed. */
    suspend fun prepareRename(uri: String, position: Position): PrepareRenameResult? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "textDocument/prepareRename",
                        PrepareRenameParams(textDocument = TextDocumentIdentifier(uri), position = position),
                        PrepareRenameParams.serializer(),
                        JsonElement.serializer(),
                    )
                }
            }?.let { element ->
                // Server returns Range | PrepareRenameResult | PrepareRenameDefaultBehavior
                when (element) {
                    is kotlinx.serialization.json.JsonObject ->
                        if (element.containsKey("placeholder")) {
                            LspJson.json.decodeFromJsonElement(PrepareRenameResult.serializer(), element)
                        } else null
                    is kotlinx.serialization.json.JsonArray -> null
                    else -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Executes a rename of the symbol at [position] to [newName]. Returns the workspace edit. */
    suspend fun rename(uri: String, position: Position, newName: String): WorkspaceEdit? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "textDocument/rename",
                        RenameParams(
                            textDocument = TextDocumentIdentifier(uri),
                            position = position,
                            newName = newName,
                        ),
                        RenameParams.serializer(),
                        WorkspaceEdit.serializer(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    // ==================== Signature help ====================

    /** Queries signature help at [position] (active signature/parameter). */
    suspend fun signatureHelp(uri: String, position: Position): SignatureHelp? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "textDocument/signatureHelp",
                        SignatureHelpParams(textDocument = TextDocumentIdentifier(uri), position = position),
                        SignatureHelpParams.serializer(),
                        SignatureHelp.serializer(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    // ==================== Navigation ====================

    /** Queries type definition (e.g. the class of an expression). */
    suspend fun typeDefinition(uri: String, position: Position): LocationResult? =
        nullableRequest(
            "textDocument/typeDefinition",
            TypeDefinitionParams(TextDocumentIdentifier(uri), position),
            TypeDefinitionParams.serializer(),
            LocationResult.serializer(),
        )

    /** Queries implementations of a symbol. */
    suspend fun implementation(uri: String, position: Position): LocationResult? =
        nullableRequest(
            "textDocument/implementation",
            ImplementationParams(TextDocumentIdentifier(uri), position),
            ImplementationParams.serializer(),
            LocationResult.serializer(),
        )

    /** Queries declarations (older servers / C/C++). */
    suspend fun declaration(uri: String, position: Position): LocationResult? =
        nullableRequest(
            "textDocument/declaration",
            DeclarationParams(TextDocumentIdentifier(uri), position),
            DeclarationParams.serializer(),
            LocationResult.serializer(),
        )

    /** Queries document links (e.g. import statements). */
    suspend fun documentLinks(uri: String): List<DocumentLink>? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "textDocument/documentLink",
                        DocumentLinkParams(textDocument = TextDocumentIdentifier(uri)),
                        DocumentLinkParams.serializer(),
                        ListSerializer(DocumentLink.serializer()),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** Queries selection ranges for [positions] (brace/expression expansion). */
    suspend fun selectionRange(uri: String, positions: List<Position>): List<SelectionRange>? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "textDocument/selectionRange",
                        SelectionRangeParams(
                            textDocument = TextDocumentIdentifier(uri),
                            positions = positions,
                        ),
                        SelectionRangeParams.serializer(),
                        ListSerializer(SelectionRange.serializer()),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    // ==================== Diagnostics (pull) ====================

    /** Pulls diagnostics for the document (`textDocument/diagnostic`). */
    suspend fun diagnostic(uri: String): List<Diagnostic>? =
        try {
            requestMutex.withLock {
                withTimeout(30_000) {
                    launcher.request(
                        "textDocument/diagnostic",
                        DocumentDiagnosticParams(textDocument = TextDocumentIdentifier(uri)),
                        DocumentDiagnosticParams.serializer(),
                        JsonElement.serializer(),
                    )
                }
            }?.let { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
                val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
                if (kind == "full") {
                    val items = obj["items"] as? kotlinx.serialization.json.JsonArray ?: return null
                    LspJson.json.decodeFromJsonElement(
                        ListSerializer(Diagnostic.serializer()),
                        items,
                    )
                } else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

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