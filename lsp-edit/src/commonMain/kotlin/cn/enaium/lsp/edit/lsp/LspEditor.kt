package cn.enaium.lsp.edit.lsp

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.imgui.extensions.markdown.Markdown
import cn.enaium.imgui.extensions.markdown.MarkdownConfigHandle
import cn.enaium.imgui.extensions.markdown.MdFormatFlags
import cn.enaium.lsp.model.*
import cn.enaium.lsp.edit.DocPos
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.EditorCodeLens
import cn.enaium.lsp.edit.EditorFoldRange
import cn.enaium.lsp.edit.EditorInlayHint
import cn.enaium.lsp.edit.PaletteIndex
import cn.enaium.lsp.edit.TokenSpan
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.min

/**
 * Binds an [Editor] widget to an [LspClient]:
 *
 * - document lifecycle (`didOpen` / `didClose`),
 * - incremental `didChange` notifications derived from the widget's edit ops,
 * - `publishDiagnostics` → gutter markers,
 * - hover requests → tooltip,
 * - completion requests → a popup overlay with keyboard navigation,
 * - go-to-definition (Ctrl+Click / F12),
 * - full-document semantic tokens → per-line color spans.
 *
 * All server round trips run on the client's coroutine scope and their
 * results are marshalled back onto the render thread via [drain], which the
 * host must call every frame before rendering the editor.
 */
class LspEditor(
    val editor: Editor,
    val client: LspClient,
    val uri: String,
    val languageId: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    /** LSP document version, incremented on every didChange. */
    var version: Int = 0
        private set

    /** The server's advertised capabilities (from initialize). */
    var serverCapabilities: ServerCapabilities? = null
        private set

    // ---- pending UI state, marshalled from the listen thread ----
    // Channels are the KMP-safe rendezvous: the listen thread pushes with
    // trySend (never suspends), the render thread drains with tryReceive.
    private val pendingDiagnostics = Channel<PublishDiagnosticsParams>(Channel.UNLIMITED)
    private val pendingMessages = Channel<MessageParams>(Channel.UNLIMITED)
    private val pendingGoto = Channel<DocPos>(Channel.UNLIMITED)
    private val pendingFormat = Channel<List<TextEdit>>(Channel.UNLIMITED)

    private fun enqueueDiagnostics(params: PublishDiagnosticsParams) {
        pendingDiagnostics.trySend(params)
    }

    private fun enqueueMessage(params: MessageParams) {
        pendingMessages.trySend(params)
    }

    private fun enqueueGoto(pos: DocPos) {
        pendingGoto.trySend(pos)
    }

    private var hoverJob: Job? = null
    private var hoverRequestSeq = 0L

    /** Written on the coroutine thread, read on the render thread. */
    @Volatile
    private var hoverResult: Hover? = null

    @Volatile
    private var hoverPosition: DocPos? = null

    @Volatile
    private var hoverInFlight = false

    /** True while the completion popup is open (see [renderCompletionPopup]). */
    var completionActive = false
        private set
    private var completionItems: List<CompletionItem> = emptyList()
    private var completionSelected = 0
    private var lastScrollSelected = -1
    private var completionAnchor: DocPos? = null
    private var completionRequestSeq = 0L
    private var completionRequest: Job? = null
    @Volatile
    private var completionDirty = false

    /**
     * While accepting a completion the replacement edit flows through
     * onTextChange like any other edit; without this flag the inserted text
     * (which ends in an identifier character) would re-trigger the popup
     * right after it was accepted.
     */
    private var suppressCompletionTrigger = false

    private var semanticTokenRequest: Job? = null
    private var semanticSpans: MutableMap<Int, List<TokenSpan>>? = null
    @Volatile
    private var semanticTokenDirty = false

    private var inlayHintRequest: Job? = null
    private var inlayHints: MutableMap<Int, List<EditorInlayHint>>? = null
    @Volatile
    private var inlayHintDirty = false

    private var foldingRequest: Job? = null
    @Volatile
    private var foldingDirty = false

    // ---- code lenses ----
    private var codeLensRequest: Job? = null
    @Volatile
    private var codeLensDirty = false
    private var codeLenses: MutableMap<Int, List<Pair<CodeLens, EditorCodeLens>>>? = null

    /** Host hook: a code lens was clicked; receives its raw LSP command. */
    var onCodeLensCommand: ((CodeLens, Command?) -> Unit)? = null

    // ---- signature help ----
    private var signatureRequest: Job? = null
    @Volatile
    private var signatureResult: SignatureHelp? = null
    @Volatile
    private var signaturePosition: DocPos? = null
    @Volatile
    private var signatureInFlight = false

    /** Host hook: document symbol tree refreshed (for structure panels). */
    var onDocumentSymbols: ((List<DocumentSymbol>) -> Unit)? = null

    /** Host hook: references resolved for a symbol (for reference panels). */
    var onReferences: ((List<Location>) -> Unit)? = null

    /** Host hook: rename workspace edit resolved (host applies it). */
    var onRename: ((WorkspaceEdit) -> Unit)? = null

    /** Callbacks for UI status (e.g. server messages). */
    var onShowMessage: ((String) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null

    /** Markdown renderer config used for hover tooltips. */
    private val markdownConfig: MarkdownConfigHandle =
        Markdown.create().also { Markdown.setFormatFlags(it, MdFormatFlags.COMMON_MARK_ALL) }

    // ==================== Lifecycle ====================

    /** Sends `initialize`, wires notifications, opens the document. */
    suspend fun start(
        processId: Int? = null,
        rootUri: String? = null,
        clientName: String = "lsp-edit",
    ) {
        // The receive loop must run before any request is sent, otherwise
        // initialize() would wait forever for a response nobody reads.
        client.startListening()

        client.onPublishDiagnostics { params ->
            enqueueDiagnostics(params)
        }
        client.onShowMessage { params ->
            enqueueMessage(params)
        }
        client.onLogMessage { params ->
            enqueueMessage(params)
        }

        val result = client.initialize(
            processId = processId,
            rootUri = rootUri,
            clientName = clientName,
        )
        serverCapabilities = result.capabilities
        client.notifyInitialized()

        // Wire the widget to the server.
        editor.onTextChange = { ops -> onEdit(ops) }
        editor.onHover = { pos -> onHover(pos) }
        editor.onHoverEnd = {
            // Mouse left the editor (or a click landed): drop any hover
            // content so the tooltip disappears.
            hoverRequestSeq++
            hoverJob?.cancel()
            hoverResult = null
            hoverPosition = null
            hoverInFlight = false
        }
        editor.tokenProvider = ::semanticTokenSpansFor
        editor.inlayHintsProvider = ::inlayHintsFor
        editor.codeLensProvider = ::codeLensesFor
        editor.onCodeLensClick = ::handleCodeLensClick
        // While the completion popup is open, Up/Down/Enter/Tab navigate and
        // accept in the popup instead of moving the caret / editing.
        editor.keysReservedByOverlay = { completionActive }

        open()
    }

    /** Sends didOpen with the current buffer content. */
    fun open() {
        version = 0
        val text = editor.getText()
        client.didOpen(uri, languageId, version, text)
        semanticSpans = null
        requestSemanticTokens()
        inlayHints = null
        requestInlayHints()
        requestFoldingRanges()
        requestCodeLenses()
        requestDocumentSymbols()
    }

    /** Sends didClose. */
    fun closeDocument() {
        client.didClose(uri)
    }

    override fun close() {
        closeDocument()
        client.close()
        scope.cancel()
        markdownConfig.close()
    }

    // ==================== Edit sync ====================

    /** Converts widget [EditOp]s into LSP content changes and notifies. */
    private fun onEdit(ops: List<cn.enaium.lsp.edit.EditOp>) {
        if (ops.isEmpty()) return
        val changes = ops.map { op ->
            val start = LspClient.toPosition(op.pos)
            if (op.insert) {
                TextDocumentContentChangeEvent(
                    range = Range(start, start),
                    text = op.text,
                )
            } else {
                val end = LspClient.toPosition(op.pos + op.text.length)
                TextDocumentContentChangeEvent(
                    range = Range(start, end),
                    text = "",
                )
            }
        }
        version++
        client.didChange(uri, version, changes)
        maybeTriggerCompletion(ops)
        requestSemanticTokens()
        requestInlayHints()
        requestFoldingRanges()
        requestCodeLenses()
        requestDocumentSymbols()
    }

    /**
     * Opens (or re-requests) completion while typing inside an identifier or
     * after a server-declared trigger character (e.g. `.`); any other edit
     * dismisses it. The sequence bump also drops an in-flight completion
     * response: without it, a fast `abc<space>` would let the `c` response
     * pop the popup open *after* the space dismissed it.
     */
    private fun maybeTriggerCompletion(ops: List<cn.enaium.lsp.edit.EditOp>) {
        if (suppressCompletionTrigger) {
            completionRequestSeq++
            closeCompletion()
            return
        }
        val last = ops.lastOrNull()
        if (last == null || !last.insert || last.text.isEmpty()) {
            completionRequestSeq++
            closeCompletion()
            return
        }
        // Trigger on the LAST inserted character, not just single-character
        // edits: IME composition, paste and multi-character insertions land
        // as one op and must open completion too (e.g. "val s = p" typed in
        // one shot still asks for "p").
        val c = last.text.last()
        val triggers = serverCapabilities?.completionProvider?.triggerCharacters
        val trigger = c.isLetterOrDigit() || c == '_' ||
            triggers?.contains(c.toString()) == true
        if (trigger) {
            filterCompletionItemsByPrefix()
            requestCompletion()
            return
        }
        completionRequestSeq++
        closeCompletion()
    }

    // ==================== Diagnostics ====================

    /**
     * Applies pending server notifications on the render thread. Call once
     * per frame before rendering the editor.
     */
    fun drain() {
        while (true) {
            val params = pendingDiagnostics.tryReceive().getOrNull() ?: break
            applyDiagnostics(params)
        }
        while (true) {
            val msg = pendingMessages.tryReceive().getOrNull() ?: break
            onShowMessage?.invoke(msg.message)
        }
        while (true) {
            val goto = pendingGoto.tryReceive().getOrNull() ?: break
            editor.setCursor(goto)
        }
        while (true) {
            val edits = pendingFormat.tryReceive().getOrNull() ?: break
            applyFormatEdits(edits)
        }
    }

    private fun applyDiagnostics(params: PublishDiagnosticsParams) {
        if (params.uri != uri) return
        editor.markers.clear()
        for (d in params.diagnostics) {
            val severity = d.severity ?: DiagnosticSeverity.Error
            val color = when (severity) {
                DiagnosticSeverity.Error -> 0xFFFF5555L
                DiagnosticSeverity.Warning -> 0xFFFFAA00L
                DiagnosticSeverity.Information -> 0xFF55A0FFL
                else -> 0xFF808080L
            }
            val message = diagnosticMessage(d)
            editor.markers[d.range.start.line] = cn.enaium.lsp.edit.EditorMarker(
                lineNumberColor = color,
                lineNumberTooltip = "${d.range.start.line + 1}: $message",
                textTooltip = message,
            )
        }
        editor.invalidateAll()
    }

    private fun diagnosticMessage(d: Diagnostic): String {
        val msg = when (val m = d.message) {
            is Documentation.StringValue -> m.value
            is Documentation.Markup -> m.value.value
            else -> ""
        }
        val code = when (val c = d.code) {
            is cn.enaium.lsp.model.DiagnosticCode.StringValue -> c.value
            is cn.enaium.lsp.model.DiagnosticCode.NumberValue -> c.value.toString()
            else -> null
        }
        return if (code != null) "$code: $msg" else msg
    }

    // ==================== Hover ====================

    private fun onHover(pos: DocPos) {
        if (hoverPosition == pos && (hoverInFlight || hoverResult != null)) return
        // Position changed (or the previous result was empty): drop stale
        // content immediately so the tooltip never shows out-of-date text.
        if (hoverPosition != pos) hoverResult = null
        hoverPosition = pos
        hoverRequestSeq++
        val seq = hoverRequestSeq
        hoverJob?.cancel()
        hoverInFlight = true
        hoverJob = scope.launch {
            val hover = client.hover(uri, LspClient.toPosition(pos))
            if (seq == hoverRequestSeq) {
                // null result hides the tooltip; non-null updates it.
                hoverResult = hover
                hoverInFlight = false
            }
        }
    }

    /** Renders the hover tooltip near the mouse when a result is pending. */
    fun renderHoverTooltip() {
        val h = hoverResult ?: return
        val text = hoverContents(h)
        if (text.isNullOrEmpty()) return
        // The markdown renderer wraps at the window's content width, but a
        // tooltip auto-sizes to its content and starts out ~0 px wide —
        // which would wrap every word onto its own line. Constrain the
        // tooltip to a minimum width so lines break at a readable measure.
        ImGui.setNextWindowSizeConstraints(ImVec2(320f, 0f), ImVec2(Float.MAX_VALUE, Float.MAX_VALUE))
        ImGui.beginTooltip()
        // Fenced code blocks in hover markdown render as read-only,
        // syntax-highlighted blocks (theme palette, no line numbers).
        cn.enaium.lsp.edit.MarkdownCode.render(
            markdownConfig,
            text,
            language = editor.language,
            palette = editor.palette,
        )
        ImGui.endTooltip()
    }

    private fun hoverContents(h: Hover): String? = when (val c = h.contents) {
        is HoverContents.Markup -> c.value.value
        is HoverContents.MarkedStrings -> c.value.joinToString("\n") {
            when (it) {
                is MarkedStringOrString.StringValue -> it.value
                is MarkedStringOrString.Marked -> it.value.value
            }
        }
        else -> null
    }

    // ==================== Completion ====================

    /**
     * Asks the server for completion at the cursor and opens the popup.
     * Called automatically on edits inside identifiers; hosts may call it
     * for Ctrl+Space.
     */
    fun requestCompletion() {
        completionDirty = true
        if (completionRequest?.isActive == true) return
        completionRequest = scope.launch {
            while (completionDirty) {
                delay(150) // debounce every iteration (see semantic tokens)
                completionDirty = false
                val pos = editor.cursor
                completionRequestSeq++
                val seq = completionRequestSeq
                val result = client.completion(uri, LspClient.toPosition(pos))
                if (seq != completionRequestSeq) {
                    // Dismissed (non-identifier edit) while in flight: do
                    // not open the popup.
                    if (!completionDirty) break
                    continue
                }
                val items = when (result) {
                    is CompletionResult.Items -> result.value
                    is CompletionResult.ListValue -> result.value.items
                    else -> emptyList()
                }
                if (items.isEmpty()) {
                    completionActive = false
                    continue
                }
                completionItems = items
                completionSelected = 0
                lastScrollSelected = -1
                // Anchor at the current cursor: when typing more identifier
                // characters the popup stays open and the anchor tracks the
                // caret, so an accept replaces the whole typed prefix.
                completionAnchor = pos
                completionActive = true
            }
        }
    }

    /**
     * While a server response is in flight, keep the popup in sync with the
     * typed prefix by filtering the previously loaded items locally.
     */
    private fun filterCompletionItemsByPrefix() {
        if (!completionActive || completionItems.isEmpty()) return
        val anchor = completionAnchor ?: return
        val pos = editor.cursor
        if (pos.line != anchor.line || pos < anchor) return
        val lineText = editor.buffer.line(pos.line)
        val prefix = lineText.substring(
            anchor.index.coerceAtMost(lineText.length),
            pos.index.coerceAtMost(lineText.length),
        )
        if (prefix.isEmpty()) return
        val filtered = completionItems.filter {
            it.label.startsWith(prefix, ignoreCase = true) ||
                it.insertText?.startsWith(prefix, ignoreCase = true) == true
        }
        if (filtered.size != completionItems.size) {
            completionItems = filtered
            if (completionSelected >= filtered.size) completionSelected = 0
        }
    }

    /** Renders the completion popup overlay (called inside the editor child). */
    fun renderCompletionPopup() {
        if (!completionActive) return
        val pos = editor.cursor
        val anchor = completionAnchor ?: return
        // The popup follows the caret, but an accept replaces [anchor, pos):
        // if the caret moved back before the anchor (undo, arrow keys) the
        // anchored prefix no longer matches, so dismiss instead of inserting
        // garbage.
        if (pos.line != anchor.line || pos < anchor) {
            closeCompletion()
            return
        }
        val x = editor.caretScreenX()
        val y = editor.caretScreenY()
        val width = 320f

        // Auto-size to the item contents (detail + description lines), capped
        // at half the display height with a scrollbar for long lists.
        ImGui.setNextWindowPos(ImVec2(x, y + editor.lineHeightPx()))
        ImGui.setNextWindowSizeConstraints(
            ImVec2(width, 0f),
            ImVec2(width, ImGui.getIO().displaySize.y * 0.5f),
        )
        ImGui.begin(
            "##completion${editor.uniqueId}",
            null,
            cn.enaium.imgui.ImGuiWindowFlags.NO_TITLE_BAR or
                cn.enaium.imgui.ImGuiWindowFlags.NO_RESIZE or
                cn.enaium.imgui.ImGuiWindowFlags.NO_MOVE or
                cn.enaium.imgui.ImGuiWindowFlags.NO_FOCUS_ON_APPEARING or
                cn.enaium.imgui.ImGuiWindowFlags.NO_NAV_FOCUS,
        )

        // Keyboard navigation: the editor leaves Up/Down/Enter/Tab to the
        // popup while it is open (keysReservedByOverlay), so the caret stays
        // put and the selection moves instead.
        if (ImGui.isKeyPressed(ImGuiKey.UP_ARROW)) {
            completionSelected = (completionSelected - 1 + completionItems.size) % completionItems.size
        }
        if (ImGui.isKeyPressed(ImGuiKey.DOWN_ARROW)) {
            completionSelected = (completionSelected + 1) % completionItems.size
        }
        // Scroll the selected item into view only when the selection moved
        // (keyboard navigation). When the user wheels through the list the
        // selection is unchanged, so the scroll position must be left alone
        // — forcing it every frame would yank the list back to the selected
        // item and make the scrollbar unusable.
        val selectionMoved = completionSelected != lastScrollSelected
        lastScrollSelected = completionSelected
        if (ImGui.isKeyPressed(ImGuiKey.ENTER) || ImGui.isKeyPressed(ImGuiKey.TAB)) {
            val item = completionItems.getOrNull(completionSelected)
            if (item != null) {
                val insert = item.insertText ?: item.label
                insertCompletion(insert)
            }
            closeCompletion()
            ImGui.end()
            return
        }
        // Escape, or a click outside the popup, dismisses it. Clicks on an
        // item below are handled by the selectable.
        if (ImGui.isKeyPressed(ImGuiKey.ESCAPE) ||
            (ImGui.isMouseClicked(0) && !ImGui.isWindowHovered(0))
        ) {
            closeCompletion()
            ImGui.end()
            return
        }

        val maxItems = 20
        val count = min(completionItems.size, maxItems)
        for (i in 0 until count) {
            val item = completionItems[i]
            val selected = i == completionSelected
            // Line 1: label + detail; line 2+: wrapped description. The
            // selectable wraps at the popup width, so the window auto-sizes
            // to the real content height.
            val text = completionItemText(item)
            if (ImGui.selectable(text, selected, cn.enaium.imgui.ImGuiSelectableFlags.NONE, ImVec2(width - 16f, 0f))) {
                val insert = item.insertText ?: item.label
                insertCompletion(insert)
                closeCompletion()
                ImGui.end()
                return
            }
            if (selected && selectionMoved) ImGui.setScrollHereY(0.5f)
        }
        ImGui.end()
    }

    /** Multi-line popup text for [item]: `label — detail` plus the description. */
    internal fun completionItemText(item: CompletionItem): String {
        val detail = item.detail ?: item.labelDetails?.detail
        val line1 = item.label + (detail?.let { "  —  $it" } ?: "")
        val desc = when (val d = item.documentation) {
            is Documentation.StringValue -> d.value
            is Documentation.Markup -> d.value.value
            null -> ""
        }
        return if (desc.isNotEmpty()) "$line1\n$desc" else line1
    }

    private fun insertCompletion(insert: String) {
        val pos = editor.cursor
        // Replace the identifier prefix under the cursor.
        val lineText = editor.buffer.line(pos.line)
        var start = pos.index
        while (start > 0) {
            val c = lineText[start - 1]
            if (c.isLetterOrDigit() || c == '_') start-- else break
        }
        val rangeStart = DocPos(pos.line, start)
        suppressCompletionTrigger = true
        try {
            editor.eraseRange(rangeStart, pos)
            editor.insertText(rangeStart, insert)
        } finally {
            suppressCompletionTrigger = false
        }
        closeCompletion()
    }

    private fun closeCompletion() {
        completionActive = false
        completionItems = emptyList()
    }

    // ==================== Go to definition ====================

    /** Requests definition for the word under [pos]; jumps when in-file. */
    fun gotoDefinition(pos: DocPos? = null) {
        val p = pos ?: editor.cursor
        scope.launch {
            val result = client.definition(uri, LspClient.toPosition(p))
            val location = when (result) {
                is LocationResult.Locations -> result.value.firstOrNull()
                is LocationResult.Links -> result.value.firstOrNull()?.let {
                    Location(it.targetUri, it.targetRange)
                }
                else -> null
            } ?: return@launch
            if (location.uri == uri) {
                enqueueGoto(LspClient.toDocPos(location.range.start))
            }
        }
    }

    // ==================== Formatting ====================

    /** Requests document formatting and applies the edits when they arrive. */
    fun formatDocument() {
        scope.launch {
            val edits = client.formatting(uri) ?: return@launch
            pendingFormat.trySend(edits)
        }
    }

    /** Applies server formatting edits on the render thread. */
    private fun applyFormatEdits(edits: List<TextEdit>) {
        if (edits.isEmpty()) return
        closeCompletion()
        // Apply bottom-up so earlier ranges stay valid. Each edit goes
        // through the editor's normal ops (which invalidate caches and
        // notify onTextChange → didChange). Completion is suppressed: the
        // formatted text ends in identifier characters and must not pop
        // the completion list open after formatting.
        val sorted = edits.sortedWith(
            compareByDescending<TextEdit> { it.range.start.line }
                .thenByDescending { it.range.start.character },
        )
        suppressCompletionTrigger = true
        try {
            for (e in sorted) {
                val start = LspClient.toDocPos(e.range.start)
                val end = LspClient.toDocPos(e.range.end)
                if (e.newText.isEmpty()) {
                    editor.eraseRange(start, end)
                } else if (start == end) {
                    editor.insertText(start, e.newText)
                } else {
                    editor.eraseRange(start, end)
                    editor.insertText(start, e.newText)
                }
            }
        } finally {
            suppressCompletionTrigger = false
        }
    }

    // ==================== Semantic tokens ====================

    /**
     * Requests full-document semantic tokens when the server supports them.
     * Requests are coalesced: while one is in flight, newer edits only mark
     * the refresh dirty and a single follow-up runs after it completes —
     * firing one request per edit would flood the server and wedge it.
     */
    private fun requestSemanticTokens() {
        val caps = serverCapabilities ?: return
        if (caps.semanticTokensProvider == null) return
        semanticTokenDirty = true
        if (semanticTokenRequest?.isActive == true) return
        semanticTokenRequest = scope.launch {
            while (semanticTokenDirty) {
                // Debounce EVERY iteration: while edits keep arriving the
                // refresh waits for a quiet gap, so requests never fire
                // back-to-back and flood the server during edit storms.
                delay(250)
                semanticTokenDirty = false
                val tokens = client.semanticTokensFull(uri) ?: continue
                if (!semanticTokenDirty) {
                    semanticSpans = decodeSemanticTokens(tokens, caps.semanticTokensProvider!!.legend)
                    editor.invalidateAll()
                }
            }
        }
    }

    /** Returns the semantic-token spans for [line], or null to fall back. */
    private fun semanticTokenSpansFor(line: Int): List<TokenSpan>? = semanticSpans?.get(line)

    internal fun decodeSemanticTokens(
        tokens: SemanticTokens,
        legend: SemanticTokensLegend,
    ): MutableMap<Int, List<TokenSpan>> {
        val result = HashMap<Int, MutableList<TokenSpan>>()
        var line = 0
        var startChar = 0
        var i = 0
        val data = tokens.data
        while (i + 4 <= data.size) {
            val deltaLine = data[i]
            val deltaStart = data[i + 1]
            val length = data[i + 2]
            val tokenType = data[i + 3]
            // tokenModifiers at i+4 is ignored (relative format not requested).
            i += 5

            // Delta encoding (LSP spec): deltaStart is relative to the
            // previous token's START when deltaLine == 0 (it is the gap
            // between the two token starts), absolute when a new line
            // starts. startChar therefore must NOT advance by the token
            // length after each token — doing so shifted every following
            // token right by the accumulated lengths.
            line += deltaLine
            if (deltaLine == 0) startChar += deltaStart else startChar = deltaStart

            val palette = mapTokenType(tokenType, legend.tokenTypes)
            if (length > 0) {
                result.getOrPut(line) { mutableListOf() }
                    .add(TokenSpan(startChar, startChar + length, palette))
            }
        }
        return result as MutableMap<Int, List<TokenSpan>>
    }

    private fun mapTokenType(type: Int, legend: List<String>): Int {
        if (type !in legend.indices) return PaletteIndex.TEXT
        return when (legend[type]) {
            "keyword" -> PaletteIndex.KEYWORD
            "comment" -> PaletteIndex.COMMENT
            "string" -> PaletteIndex.STRING
            "number" -> PaletteIndex.NUMBER
            "type", "class", "enum", "interface", "struct", "namespace" -> PaletteIndex.KNOWN_IDENTIFIER
            "function", "method", "macro" -> PaletteIndex.DECLARATION
            "operator" -> PaletteIndex.PUNCTUATION
            "preprocessor" -> PaletteIndex.PREPROCESSOR
            else -> PaletteIndex.TEXT
        }
    }

    // ==================== Folding ====================

    /**
     * Requests foldable ranges when the server supports them (coalesced like
     * semantic tokens). The longer debounce lets the server's snapshot
     * commit land before the request fires.
     */
    private fun requestFoldingRanges() {
        val caps = serverCapabilities ?: return
        if (caps.foldingRangeProvider == null) return
        foldingDirty = true
        if (foldingRequest?.isActive == true) return
        foldingRequest = scope.launch {
            while (foldingDirty) {
                delay(1_000) // debounce every iteration (see semantic tokens)
                foldingDirty = false
                val ranges = try {
                    withTimeout(30_000) { client.foldingRange(uri) }
                } catch (e: TimeoutCancellationException) {
                    null // server did not answer in time; retry if dirty
                }
                if (ranges != null && !foldingDirty) {
                    applyFoldingRanges(ranges.map { EditorFoldRange(it.startLine, it.endLine) })
                }
            }
        }
    }

    /** Applies [ranges], restoring the collapse state of ranges that remain. */
    private fun applyFoldingRanges(ranges: List<EditorFoldRange>) {
        val folded = editor.getFoldRanges().mapNotNull { r ->
            if (editor.isLineFolded(r.startLine)) r.startLine else null
        }
        editor.setFoldRanges(ranges)
        // Restore the previous collapse state for ranges that still exist.
        for (start in folded) {
            if (editor.foldAt(start) != null && !editor.isLineFolded(start)) {
                editor.toggleFold(start)
            }
        }
    }


    // ==================== Inlay hints ====================

    /** Requests inlay hints for the whole document when supported (coalesced like semantic tokens). */
    private fun requestInlayHints() {
        val caps = serverCapabilities ?: return
        if (caps.inlayHintProvider == null) return
        inlayHintDirty = true
        if (inlayHintRequest?.isActive == true) return
        inlayHintRequest = scope.launch {
            while (inlayHintDirty) {
                delay(250) // debounce every iteration (see semantic tokens)
                inlayHintDirty = false
                val lineCount = editor.lineCount()
                if (lineCount == 0) continue
                val endLine = lineCount - 1
                val range = Range(
                    start = Position(0, 0),
                    end = Position(endLine, editor.buffer.line(endLine).length),
                )
                val hints = client.inlayHint(uri, range) ?: continue
                if (!inlayHintDirty) {
                    inlayHints = decodeInlayHints(hints)
                    editor.invalidateAll()
                }
            }
        }
    }

    /** Returns the inlay hints for [line], or null to render none. */
    private fun inlayHintsFor(line: Int): List<EditorInlayHint>? {
        val lineHints = inlayHints?.get(line) ?: return null
        // Hints may be stale right after an edit; drop ones past the line end.
        val len = editor.buffer.line(line).length
        return lineHints.filter { it.position.index <= len }
    }

    private fun decodeInlayHints(hints: List<InlayHint>): MutableMap<Int, List<EditorInlayHint>> {
        val result = HashMap<Int, MutableList<EditorInlayHint>>()
        for (h in hints) {
            val label = when (val l = h.label) {
                is InlayHintLabel.StringValue -> l.value
                is InlayHintLabel.Parts -> l.value.joinToString("") { it.value }
            }
            if (label.isEmpty()) continue
            result.getOrPut(h.position.line) { mutableListOf() }
                .add(EditorInlayHint(LspClient.toDocPos(h.position), label))
        }
        return result as MutableMap<Int, List<EditorInlayHint>>
    }

    // ==================== Code lenses ====================

    /**
     * Requests code lenses when the server supports them (coalesced like
     * semantic tokens). Lenses without a command are resolved individually
     * so clicks can dispatch the server command.
     */
    private fun requestCodeLenses() {
        val caps = serverCapabilities ?: return
        if (caps.codeLensProvider == null) return
        codeLensDirty = true
        if (codeLensRequest?.isActive == true) return
        codeLensRequest = scope.launch {
            while (codeLensDirty) {
                delay(400) // debounce every iteration (see semantic tokens)
                codeLensDirty = false
                val lenses = client.codeLens(uri) ?: continue
                if (!codeLensDirty) {
                    codeLenses = decodeCodeLenses(lenses)
                    editor.invalidateAll()
                }
            }
        }
    }

    /** Returns the code lenses for [line], or null to render none. */
    private fun codeLensesFor(line: Int): List<EditorCodeLens>? {
        val lineLenses = codeLenses?.get(line) ?: return null
        return lineLenses.map { it.second }
    }

    private fun decodeCodeLenses(lenses: List<CodeLens>): MutableMap<Int, List<Pair<CodeLens, EditorCodeLens>>> {
        val result = HashMap<Int, MutableList<Pair<CodeLens, EditorCodeLens>>>()
        for (l in lenses) {
            val title = l.command?.title ?: continue
            if (title.isEmpty()) continue
            result.getOrPut(l.range.start.line) { mutableListOf() }
                .add(l to EditorCodeLens(l.range.start.line, title, l.command?.command))
        }
        return result as MutableMap<Int, List<Pair<CodeLens, EditorCodeLens>>>
    }

    /** Handles a code lens click: resolves missing commands, dispatches. */
    private fun handleCodeLensClick(lens: EditorCodeLens) {
        val raw = codeLenses?.values?.flatten()?.firstOrNull {
            it.second === lens
        }?.first ?: return
        if (raw.command != null) {
            onCodeLensCommand?.invoke(raw, raw.command)
            return
        }
        // Resolve the lens to fill its command.
        scope.launch {
            val resolved = client.codeLensResolve(raw) ?: return@launch
            if (resolved.command != null) {
                onCodeLensCommand?.invoke(resolved, resolved.command)
            }
        }
    }

    // ==================== Signature help ====================

    /** Requests signature help at the cursor (debounced). */
    fun requestSignatureHelp() {
        val caps = serverCapabilities ?: return
        if (caps.signatureHelpProvider == null) return
        signatureRequest?.cancel()
        signatureRequest = scope.launch {
            delay(200) // debounce: let the caret settle after typing
            val pos = editor.cursor
            signatureRequestSeq++
            val seq = signatureRequestSeq
            val result = client.signatureHelp(uri, LspClient.toPosition(pos))
            if (seq == signatureRequestSeq) {
                signatureResult = result
                signaturePosition = pos
                signatureInFlight = false
            }
        }
        signatureInFlight = true
    }

    private var signatureRequestSeq = 0L

    /** Renders the signature help popup near the caret (called after editor render). */
    fun renderSignatureHelp() {
        val h = signatureResult ?: return
        if (h.signatures.isEmpty()) return
        val sig = h.signatures.getOrNull(h.activeSignature ?: 0) ?: return
        val x = editor.caretScreenX()
        val y = editor.caretScreenY()
        ImGui.setNextWindowPos(ImVec2(x, y - editor.lineHeightPx() * 2.2f))
        ImGui.setNextWindowSizeConstraints(
            ImVec2(320f, 0f),
            ImVec2(640f, ImGui.getIO().displaySize.y * 0.4f),
        )
        ImGui.begin(
            "##signature${editor.uniqueId}",
            null,
            cn.enaium.imgui.ImGuiWindowFlags.NO_TITLE_BAR or
                cn.enaium.imgui.ImGuiWindowFlags.NO_RESIZE or
                cn.enaium.imgui.ImGuiWindowFlags.NO_MOVE or
                cn.enaium.imgui.ImGuiWindowFlags.NO_FOCUS_ON_APPEARING or
                cn.enaium.imgui.ImGuiWindowFlags.NO_NAV_FOCUS,
        )
        val activeParam = h.activeParameter ?: sig.activeParameter ?: 0
        // Highlight the active parameter inside the label.
        val label = sig.label
        val param = sig.parameters?.getOrNull(activeParam)
        val paramLabel = param?.let { p ->
            when (val l = p.label) {
                is ParameterLabel.StringValue -> l.value
                is ParameterLabel.Offsets -> {
                    val (s, e) = l.value
                    label.substring(s.coerceIn(0, label.length), e.coerceIn(0, label.length))
                }
                else -> null
            }
        }
        if (paramLabel != null && paramLabel.isNotEmpty()) {
            val start = label.indexOf(paramLabel)
            if (start >= 0) {
                ImGui.text(label.substring(0, start))
                ImGui.sameLine(0f, 0f)
                ImGui.pushStyleColor(
                    cn.enaium.imgui.ImGuiCol.TEXT,
                    ImVec4(0.9f, 0.75f, 0.48f, 1f),
                )
                ImGui.text(paramLabel)
                ImGui.popStyleColor()
                ImGui.sameLine(0f, 0f)
                ImGui.text(label.substring(start + paramLabel.length))
                ImGui.end()
                return
            }
        }
        ImGui.textWrapped(label)
        ImGui.end()
    }

    // ==================== Document symbols ====================

    /** Requests the document symbol tree (coalesced). */
    private fun requestDocumentSymbols() {
        val caps = serverCapabilities ?: return
        if (caps.documentSymbolProvider == null) return
        if (documentSymbolRequest?.isActive == true) return
        documentSymbolRequest = scope.launch {
            delay(400)
            val symbols = client.documentSymbols(uri) ?: return@launch
            onDocumentSymbols?.invoke(symbols)
        }
    }

    private var documentSymbolRequest: Job? = null

    // ==================== References / rename ====================

    /** Requests references for the word under [pos] (or the cursor). */
    fun requestReferences(pos: DocPos? = null) {
        val p = pos ?: editor.cursor
        scope.launch {
            val locations = client.references(uri, LspClient.toPosition(p)) ?: return@launch
            onReferences?.invoke(locations)
        }
    }

    /** Prepares and executes a rename of the symbol under [pos] to [newName]. */
    fun requestRename(newName: String, pos: DocPos? = null) {
        val p = pos ?: editor.cursor
        scope.launch {
            val edit = client.rename(uri, LspClient.toPosition(p), newName) ?: return@launch
            onRename?.invoke(edit)
        }
    }

    /** Checks whether the symbol under [pos] can be renamed. */
    fun canRename(pos: DocPos? = null, onResult: (Boolean) -> Unit) {
        val p = pos ?: editor.cursor
        scope.launch {
            val r = client.prepareRename(uri, LspClient.toPosition(p))
            onResult(r != null)
        }
    }

    /** Requests type definition for the word under [pos]. */
    fun gotoTypeDefinition(pos: DocPos? = null) {
        val p = pos ?: editor.cursor
        scope.launch {
            val result = client.typeDefinition(uri, LspClient.toPosition(p))
            val location = when (result) {
                is LocationResult.Locations -> result.value.firstOrNull()
                is LocationResult.Links -> result.value.firstOrNull()?.let {
                    Location(it.targetUri, it.targetRange)
                }
                else -> null
            } ?: return@launch
            if (location.uri == uri) {
                enqueueGoto(LspClient.toDocPos(location.range.start))
            }
        }
    }

    /** Requests implementations of the symbol under [pos]. */
    fun gotoImplementation(pos: DocPos? = null) {
        val p = pos ?: editor.cursor
        scope.launch {
            val result = client.implementation(uri, LspClient.toPosition(p))
            val location = when (result) {
                is LocationResult.Locations -> result.value.firstOrNull()
                is LocationResult.Links -> result.value.firstOrNull()?.let {
                    Location(it.targetUri, it.targetRange)
                }
                else -> null
            } ?: return@launch
            if (location.uri == uri) {
                enqueueGoto(LspClient.toDocPos(location.range.start))
            }
        }
    }

}