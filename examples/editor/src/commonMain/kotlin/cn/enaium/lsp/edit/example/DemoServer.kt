package cn.enaium.lsp.edit.example

import cn.enaium.lsp.LanguageServerLauncher
import cn.enaium.lsp.LanguageServer
import cn.enaium.lsp.TextDocumentService
import cn.enaium.lsp.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * An in-process demo language server for the example: a tiny "mini-kotlin"
 * language with keywords, hover docs, completion, go-to-definition and
 * full-document semantic tokens. Runs on a background coroutine over an
 * in-memory transport pair, so the example works headless with zero
 * external binaries.
 */
class DemoLanguageServer : LanguageServer {

    private val documents = HashMap<String, String>()

    override fun initialize(params: InitializeParams): InitializeResult =
        InitializeResult(
            capabilities = ServerCapabilities(
                textDocumentSync = TextDocumentSync.Kind(TextDocumentSyncKind.Full),
                hoverProvider = HoverProvider.Enabled(true),
                completionProvider = CompletionOptions(
                    triggerCharacters = listOf("."),
                    resolveProvider = false,
                ),
                definitionProvider = DefinitionProvider.Enabled(true),
                semanticTokensProvider = SemanticTokensWithRegistrationOptions(
                    legend = SemanticTokensLegend(
                        tokenTypes = listOf(
                            "keyword", "comment", "string", "number", "type", "function",
                        ),
                        tokenModifiers = listOf("declaration"),
                    ),
                    full = BooleanOrDelta.Enabled(true),
                ),
                inlayHintProvider = InlayHintProvider.Enabled(true),
                foldingRangeProvider = FoldingRangeProvider.Enabled(true),
            ),
            serverInfo = ServerInfo(name = "lsp-edit-demo", version = "1.0.0"),
        )

    override fun initialized(params: InitializedParams) {}

    override fun shutdown(): Any? = null

    override fun exit() {}

    override fun textDocumentService(): TextDocumentService = DemoTextDocumentService()

    override fun workspaceService(): cn.enaium.lsp.WorkspaceService? = null

    override fun windowService(): cn.enaium.lsp.WindowService? = null

    // ==================== Demo text document service ====================

    inner class DemoTextDocumentService : TextDocumentService {

        override fun didOpen(params: DidOpenTextDocumentParams) {
            documents[params.textDocument.uri] = params.textDocument.text
        }

        override fun didChange(params: DidChangeTextDocumentParams) {
            // Full sync: last change carries the whole document.
            val last = params.contentChanges.lastOrNull() ?: return
            documents[params.textDocument.uri] = last.text
        }

        override fun didClose(params: DidCloseTextDocumentParams) {
            documents.remove(params.textDocument.uri)
        }

        override fun hover(params: HoverParams): Hover? {
            val text = documents[params.textDocument.uri] ?: return null
            val word = wordAt(text, params.position) ?: return null
            val doc = KEYWORD_DOCS[word] ?: "identifier `$word`"
            return Hover(
                contents = HoverContents.Markup(
                    MarkupContent(
                        kind = MarkupKind.Markdown,
                        value = "**$word**  \n$doc",
                    ),
                ),
                range = Range(params.position, params.position),
            )
        }

        override fun completion(params: CompletionParams): CompletionResult? {
            val text = documents[params.textDocument.uri] ?: return null
            val prefix = identifierPrefixBefore(text, params.position)
            val items = KEYWORDS.filter { it.startsWith(prefix) }.map { word ->
                CompletionItem(
                    label = word,
                    kind = CompletionItemKind.Keyword,
                    detail = KEYWORD_DOCS[word] ?: "",
                )
            }
            // Also offer identifiers already used in the document.
            val docWords = text
                .split(Regex("[^A-Za-z0-9_]+"))
                .filter { it.isNotEmpty() && it !in KEYWORDS }
                .distinct()
                .filter { it.startsWith(prefix) }
                .map { CompletionItem(label = it, kind = CompletionItemKind.Variable) }
            val all = (items + docWords).distinctBy { it.label }
            return if (all.isEmpty()) null else CompletionResult.ListValue(CompletionList(items = all))
        }

        override fun definition(params: DefinitionParams): LocationResult? {
            val text = documents[params.textDocument.uri] ?: return null
            val word = wordAt(text, params.position) ?: return null
            val first = text.indexOf(word)
            if (first < 0) return null
            val (line, index) = offsetToLineIndex(text, first)
            return LocationResult.Locations(
                listOf(
                    Location(
                        uri = params.textDocument.uri,
                        range = Range(
                            start = Position(line, index),
                            end = Position(line, index + word.length),
                        ),
                    ),
                ),
            )
        }

        override fun semanticTokensFull(params: SemanticTokensParams): SemanticTokens? {
            val text = documents[params.textDocument.uri] ?: return null
            return SemanticTokens(data = tokenize(text))
        }

        override fun inlayHint(params: InlayHintParams): List<InlayHint>? {
            val text = documents[params.textDocument.uri] ?: return null
            return inlayHintsFor(text)
        }

        override fun foldingRange(params: FoldingRangeRequestParams): List<FoldingRange>? {
            val text = documents[params.textDocument.uri] ?: return null
            return foldingRangesFor(text)
        }
    }

    // ==================== Folding ====================

    /**
     * Demo folding: brace-matched blocks. Returns one range per `{ ... }`
     * block (function bodies, classes, control flow), nesting naturally.
     */
    private fun foldingRangesFor(text: String): List<FoldingRange>? {
        val lines = text.split("\n")
        val ranges = mutableListOf<FoldingRange>()
        val stack = ArrayDeque<Int>() // open-brace line of each open block
        for (l in lines.indices) {
            val line = lines[l]
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    '{' -> stack.addLast(l)
                    '}' -> {
                        val start = stack.removeLastOrNull() ?: break
                        if (start < l) {
                            ranges += FoldingRange(
                                startLine = start,
                                endLine = l,
                                kind = if (lines[start].contains("fun ")) "region" else null,
                            )
                        }
                    }
                    '"' -> {
                        i++
                        while (i < line.length && line[i] != '"') i++
                    }
                    else -> {}
                }
                i++
            }
        }
        return ranges.ifEmpty { null }
    }

    // ==================== Inlay hints ====================

    // ==================== Inlay hints ====================

    /**
     * Demo inlay hints: parameter names at known call sites and inferred
     * types on `val`/`var` declarations without an explicit type annotation.
     * Single-line calls only; nested calls are skipped.
     */
    private fun inlayHintsFor(text: String): List<InlayHint>? {
        val hints = mutableListOf<InlayHint>()
        val lines = text.split("\n")

        for ((lineIdx, line) in lines.withIndex()) {
            // --- Type hints: `val NAME = literal` / `var NAME = literal` ---
            val decl = Regex("\\b(val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=")
            val m = decl.find(line)
            if (m != null) {
                val ident = m.groups[2]!!.value
                val rhs = line.substring(m.range.last + 1).trimStart()
                val type = when {
                    rhs.startsWith("\"") -> "String"
                    rhs.startsWith("true") || rhs.startsWith("false") -> "Boolean"
                    rhs.startsWith("0x") || (rhs.isNotEmpty() && rhs[0].isDigit()) -> {
                        if (rhs.substringBefore(' ').contains('.')) "Double" else "Int"
                    }
                    else -> null
                }
                if (type != null) {
                    hints += InlayHint(
                        position = Position(lineIdx, m.range.first + ident.length + 1),
                        label = InlayHintLabel.StringValue(": $type"),
                        kind = InlayHintKind.Type,
                    )
                }
            }

            // --- Parameter-name hints at known call sites ---
            for ((name, params) in KNOWN_CALLS) {
                var from = 0
                while (true) {
                    val open = line.indexOf("$name(", from)
                    if (open < 0) break
                    val close = line.indexOf(')', open + name.length + 1)
                    if (close > 0) {
                        val argSpan = line.substring(open + name.length + 1, close)
                        if ('(' !in argSpan && ')' !in argSpan && ',' in argSpan) {
                            var argStart = open + name.length + 1
                            for (pi in params.indices) {
                                if (pi > 0) {
                                    val comma = line.indexOf(',', argStart)
                                    if (comma < 0 || comma > close) break
                                    argStart = comma + 1
                                }
                                // Skip whitespace to the argument start.
                                var pos = argStart
                                while (pos < close && line[pos] == ' ') pos++
                                if (pos >= close) break
                                hints += InlayHint(
                                    position = Position(lineIdx, pos),
                                    label = InlayHintLabel.StringValue("${params[pi]}: "),
                                    kind = InlayHintKind.Parameter,
                                )
                            }
                        }
                    }
                    from = open + 1
                }
            }
        }
        return hints.ifEmpty { null }
    }

    // ==================== Mini tokenizer ====================

    // ==================== Mini tokenizer ====================

    private fun tokenize(text: String): List<Int> {
        // LSP semantic-token delta encoding: each token is
        // [deltaLine, deltaStart, length, tokenType, tokenModifiers].
        // deltaStart is relative to the previous token's end on the same
        // line (or the line start when deltaLine != 0).
        val out = mutableListOf<Int>()
        var lastLine = 0
        var lastEnd = 0
        var emitted = false
        fun emit(lineIdx: Int, start: Int, length: Int, tokenType: Int) {
            val deltaLine = if (emitted) lineIdx - lastLine else 0
            val deltaStart = if (!emitted || deltaLine != 0) start else start - lastEnd
            out += deltaLine
            out += deltaStart
            out += length
            out += tokenType
            out += 0 // tokenModifiers
            lastLine = lineIdx
            lastEnd = start + length
            emitted = true
        }
        val lines = text.split("\n")
        for (l in lines.indices) {
            val s = lines[l]
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c.isLetter() || c == '_') {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    val word = s.substring(start, i)
                    val type = when {
                        word in KEYWORDS -> 0 // keyword
                        word.firstOrNull()?.isUpperCase() == true -> 4 // type
                        else -> 5 // identifier
                    }
                    emit(l, start, i - start, type)
                } else if (c.isDigit()) {
                    val start = i
                    while (i < s.length && s[i].isDigit()) i++
                    emit(l, start, i - start, 3)
                } else if (c == '"') {
                    val start = i
                    i++
                    while (i < s.length && s[i] != '"') i++
                    if (i < s.length) i++
                    emit(l, start, i - start, 2)
                } else if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                    emit(l, i, s.length - i, 1)
                    break
                } else {
                    i++
                }
            }
        }
        return out
    }

    // ==================== Helpers ====================

    private fun wordAt(text: String, pos: Position): String? {
        val lines = text.split("\n")
        val line = lines.getOrNull(pos.line) ?: return null
        var start = pos.character
        var end = pos.character
        val isWord = { c: Char -> c.isLetterOrDigit() || c == '_' }
        while (start > 0 && isWord(line[start - 1])) start--
        while (end < line.length && isWord(line[end])) end++
        if (start == end) return null
        return line.substring(start, end)
    }

    private fun identifierPrefixBefore(text: String, pos: Position): String {
        val lines = text.split("\n")
        val line = lines.getOrNull(pos.line) ?: return ""
        var i = pos.character - 1
        while (i >= 0 && (line[i].isLetterOrDigit() || line[i] == '_')) i--
        return line.substring(i + 1, pos.character)
    }

    private fun offsetToLineIndex(text: String, offset: Int): Pair<Int, Int> {
        var remaining = offset
        var line = 0
        while (line < text.length) {
            val nl = text.indexOf('\n', line)
            val lineEnd = if (nl < 0) text.length else nl
            if (remaining <= lineEnd - line) return line to remaining
            remaining -= (lineEnd - line + 1)
            line = lineEnd + 1
        }
        return 0 to 0
    }

    companion object {
        /** (call name, parameter names) — drives parameter-name inlay hints. */
        private val KNOWN_CALLS = listOf(
            "println" to listOf("message"),
            "distance" to listOf("a", "b"),
            "Point" to listOf("x", "y"),
        )

        val KEYWORDS = listOf(
            "fun", "val", "var", "if", "else", "when", "for", "while",
            "return", "class", "object", "interface", "enum", "import",
            "package", "true", "false", "null", "this", "override",
            "private", "internal", "public", "suspend", "data", "sealed",
        )
        val KEYWORD_DOCS = mapOf(
            "fun" to "Declares a function.",
            "val" to "Declares a read-only property.",
            "var" to "Declares a mutable property.",
            "class" to "Declares a class.",
            "object" to "Declares a singleton object.",
            "interface" to "Declares an interface.",
            "suspend" to "Marks a suspending function.",
            "when" to "Expression-based branch construct.",
            "data" to "Marks a data class.",
            "sealed" to "Marks a sealed class hierarchy.",
        )
    }
}

/** Runs [server] on a background coroutine, connected to the client side. */
fun runDemoServer(
    pair: cn.enaium.lsp.edit.lsp.InMemoryTransportPair,
    server: DemoLanguageServer,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        val launcher = LanguageServerLauncher(pair.b, server)
        launcher.listen()
    }
}