package cn.enaium.lsp.edit.lsp

import cn.enaium.lsp.LanguageServer
import cn.enaium.lsp.LanguageServerLauncher
import cn.enaium.lsp.TextDocumentService
import cn.enaium.lsp.dap.DebugAdapter
import cn.enaium.lsp.dap.DebugAdapterLauncher
import cn.enaium.lsp.dap.model.*
import cn.enaium.lsp.model.*
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests: a real [LspClient] speaking to a real server built with
 * lsp-kmp's [LanguageServerLauncher] over the in-memory transport pair.
 * Verifies the full wire path — handshake, document sync, requests,
 * notifications — with no sockets or processes.
 */
class LspClientTest {

    private class TestServer : LanguageServer {
        var lastDocument: String? = null
        var diagnosticsPublished = false

        override fun initialize(params: InitializeParams): InitializeResult =
            InitializeResult(
                capabilities = ServerCapabilities(
                    textDocumentSync = TextDocumentSync.Kind(TextDocumentSyncKind.Full),
                    hoverProvider = HoverProvider.Enabled(true),
                    completionProvider = CompletionOptions(triggerCharacters = listOf(".")),
                    definitionProvider = DefinitionProvider.Enabled(true),
                    inlayHintProvider = InlayHintProvider.Enabled(true),
                    foldingRangeProvider = FoldingRangeProvider.Enabled(true),
                ),
                serverInfo = ServerInfo(name = "test-server", version = "1.0.0"),
            )

        override fun shutdown(): Any? = null
        override fun exit() {}

        override fun workspaceService(): cn.enaium.lsp.WorkspaceService? = null

        override fun windowService(): cn.enaium.lsp.WindowService? = null

        override fun textDocumentService(): TextDocumentService = object : TextDocumentService {
            override fun didOpen(params: DidOpenTextDocumentParams) {
                lastDocument = params.textDocument.text
            }

            override fun didChange(params: DidChangeTextDocumentParams) {
                lastDocument = params.contentChanges.lastOrNull()?.text
                diagnosticsPublished = true
            }

            override fun hover(params: HoverParams): Hover =
                Hover(
                    contents = HoverContents.Markup(
                        MarkupContent(MarkupKind.PlainText, "hover for ${params.position.line}"),
                    ),
                )

            override fun completion(params: CompletionParams): CompletionResult =
                CompletionResult.ListValue(
                    CompletionList(
                        items = listOf(
                            CompletionItem(label = "alpha", kind = CompletionItemKind.Keyword),
                            CompletionItem(label = "beta", kind = CompletionItemKind.Variable),
                        ),
                    ),
                )

            override fun definition(params: DefinitionParams): LocationResult =
                LocationResult.Locations(
                    listOf(Location(params.textDocument.uri, Range(Position(0, 0), Position(0, 4)))),
                )

            override fun inlayHint(params: InlayHintParams): List<InlayHint> =
                listOf(
                    InlayHint(
                        position = Position(0, 5),
                        label = InlayHintLabel.StringValue(": Int"),
                        kind = InlayHintKind.Type,
                    ),
                    InlayHint(
                        position = Position(0, 9),
                        label = InlayHintLabel.StringValue("message: "),
                        kind = InlayHintKind.Parameter,
                    ),
                )

            override fun foldingRange(params: FoldingRangeRequestParams): List<FoldingRange> =
                listOf(
                    FoldingRange(startLine = 0, endLine = 3, kind = "region"),
                    FoldingRange(startLine = 5, endLine = 7),
                )
        }
    }

    private class ServerHandle(val scope: CoroutineScope, val launcher: LanguageServerLauncher)

    private fun startServer(pair: InMemoryTransportPair, server: TestServer): ServerHandle {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val launcher = LanguageServerLauncher(pair.b, server)
        scope.launch {
            launcher.listen()
        }
        return ServerHandle(scope, launcher)
    }

    @Test
    fun handshakeAndDocumentSync() = runBlocking {
        val pair = InMemoryTransportPair()
        val server = TestServer()
        val handle = startServer(pair, server)
        val serverScope = handle.scope

        val client = LspClient(pair.a)
        client.startListening()

        val result = client.initialize(clientName = "lsp-edit-test")
        assertEquals("test-server", result.serverInfo?.name)
        assertNotNull(client.getServerCapabilities())
        client.notifyInitialized()

        client.didOpen("file:///t.txt", "plaintext", 1, "hello")
        client.didChange(
            "file:///t.txt",
            2,
            listOf(TextDocumentContentChangeEvent(range = null, text = "hello world")),
        )

        // Give the listen loop a moment to deliver notifications.
        delay(100)
        assertEquals("hello world", server.lastDocument)
        assertTrue(server.diagnosticsPublished)

        client.close()
        pair.close()
        serverScope.cancel()
    }

    @Test
    fun hoverAndCompletionRoundTrip() = runBlocking {
        val pair = InMemoryTransportPair()
        val server = TestServer()
        val handle = startServer(pair, server)
        val serverScope = handle.scope

        val client = LspClient(pair.a)
        client.startListening()
        client.initialize(clientName = "lsp-edit-test")
        client.notifyInitialized()
        client.didOpen("file:///t.txt", "plaintext", 1, "hello world")

        val hover = client.hover("file:///t.txt", Position(0, 1))
        assertNotNull(hover)
        val content = (hover.contents as HoverContents.Markup).value.value
        assertEquals("hover for 0", content)

        val completion = client.completion("file:///t.txt", Position(0, 5))
        val items = when (completion) {
            is CompletionResult.ListValue -> completion.value.items
            is CompletionResult.Items -> completion.value
            null -> emptyList()
        }
        assertEquals(listOf("alpha", "beta"), items.map { it.label })

        val definition = client.definition("file:///t.txt", Position(0, 0))
        val locations = when (definition) {
            is LocationResult.Locations -> definition.value
            is LocationResult.Links -> definition.value.map { Location(it.targetUri, it.targetRange) }
            null -> emptyList()
        }
        assertEquals(1, locations.size)
        assertEquals(0, locations[0].range.start.line)

        client.close()
        pair.close()
        serverScope.cancel()
    }

    @Test
    fun inlayHintRoundTrip() = runBlocking {
        val pair = InMemoryTransportPair()
        val server = TestServer()
        val handle = startServer(pair, server)
        val serverScope = handle.scope

        val client = LspClient(pair.a)
        client.startListening()
        client.initialize(clientName = "lsp-edit-test")
        client.notifyInitialized()
        client.didOpen("file:///t.txt", "plaintext", 1, "fun call(msg: String) = println(msg)")

        val range = Range(Position(0, 0), Position(0, 40))
        val hints = client.inlayHint("file:///t.txt", range)
        assertNotNull(hints)
        assertEquals(2, hints.size)

        assertEquals(Position(0, 5), hints[0].position)
        val label0 = hints[0].label as InlayHintLabel.StringValue
        assertEquals(": Int", label0.value)
        assertEquals(InlayHintKind.Type, hints[0].kind)

        assertEquals(Position(0, 9), hints[1].position)
        val label1 = hints[1].label as InlayHintLabel.StringValue
        assertEquals("message: ", label1.value)
        assertEquals(InlayHintKind.Parameter, hints[1].kind)

        client.close()
        pair.close()
        serverScope.cancel()
    }

    @Test
    fun foldingRangeRoundTrip() = runBlocking {
        val pair = InMemoryTransportPair()
        val server = TestServer()
        val handle = startServer(pair, server)
        val serverScope = handle.scope

        val client = LspClient(pair.a)
        client.startListening()
        client.initialize(clientName = "lsp-edit-test")
        client.notifyInitialized()
        client.didOpen("file:///t.txt", "plaintext", 1, "fun a() {\n    x()\n}\nfun b() {\n    y()\n}")

        val ranges = client.foldingRange("file:///t.txt")
        assertNotNull(ranges)
        assertEquals(2, ranges.size)
        assertEquals(FoldingRange(startLine = 0, endLine = 3, kind = "region"), ranges[0])
        assertEquals(FoldingRange(startLine = 5, endLine = 7), ranges[1])

        client.close()
        pair.close()
        serverScope.cancel()
    }

    @Test
    fun diagnosticsNotificationReachesClient() = runBlocking {
        val pair = InMemoryTransportPair()
        val server = TestServer()
        val handle = startServer(pair, server)
        val serverScope = handle.scope

        val client = LspClient(pair.a)
        client.startListening()
        var received: PublishDiagnosticsParams? = null
        client.onPublishDiagnostics { received = it }

        client.initialize(clientName = "lsp-edit-test")
        client.notifyInitialized()
        client.didOpen("file:///t.txt", "plaintext", 1, "x")

        // The server pushes diagnostics through its LanguageClient facade.
        handle.launcher.client.publishDiagnostics(
            PublishDiagnosticsParams(
                uri = "file:///t.txt",
                diagnostics = listOf(
                    Diagnostic(
                        range = Range(Position(0, 0), Position(0, 1)),
                        severity = DiagnosticSeverity.Error,
                        message = Documentation.StringValue("boom"),
                    ),
                ),
            ),
        )

        delay(100)
        assertNotNull(received)
        assertEquals("file:///t.txt", received!!.uri)
        assertEquals(1, received!!.diagnostics.size)
        assertEquals(DiagnosticSeverity.Error, received!!.diagnostics[0].severity)

        client.close()
        pair.close()
        serverScope.cancel()
    }
}

/**
 * End-to-end DAP tests: a real [cn.enaium.lsp.edit.dap.DapClient] speaking
 * to a [cn.enaium.lsp.dap.DebugAdapterLauncher] over the in-memory pair.
 */
class DapClientTest {

    private class ScriptedAdapter : cn.enaium.lsp.dap.DebugAdapter {
        var breakpointLines: Set<Int> = emptySet()
        override fun initialize(request: InitializeRequestArguments): Capabilities =
            Capabilities(supportsConfigurationDoneRequest = true)
        override fun continue_(args: ContinueArguments): ContinueResponseBody {
            stopLine = 10
            return ContinueResponseBody(allThreadsContinued = true)
        }
        override fun setBreakpoints(args: SetBreakpointsArguments): SetBreakpointsResponseBody {
            breakpointLines = args.breakpoints?.map { it.line }?.toSet() ?: emptySet()
            return SetBreakpointsResponseBody(
                breakpoints = breakpointLines.map { Breakpoint(id = it, verified = true, line = it) },
            )
        }
        override fun threads(): ThreadsResponseBody = ThreadsResponseBody(listOf(Thread(1, "main")))
        override fun stackTrace(args: StackTraceArguments): StackTraceResponseBody =
            StackTraceResponseBody(
                listOf(
                    StackFrame(
                        id = 1,
                        name = "main",
                        source = Source(name = "demo.kt", path = "file:///demo.kt"),
                        line = stopLine,
                        column = 1,
                    ),
                ),
            )
        override fun scopes(args: ScopesArguments): ScopesResponseBody =
            ScopesResponseBody(listOf(Scope("Locals", 100)))
        override fun variables(args: VariablesArguments): VariablesResponseBody =
            VariablesResponseBody(
                listOf(Variable("x", "1", "Int"), Variable("y", "2.0", "Double")),
            )
        override fun evaluate(args: EvaluateArguments): EvaluateResponseBody =
            EvaluateResponseBody(result = "42", type = "Int")
        var stopLine = 1
    }

    private fun runAdapter(pair: InMemoryTransportPair, adapter: ScriptedAdapter) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val launcher = cn.enaium.lsp.dap.DebugAdapterLauncher(pair.b, adapter)
        scope.launch { launcher.listen() }
    }

    @Test
    fun dapRoundTrip() = runBlocking {
        val pair = InMemoryTransportPair()
        val adapter = ScriptedAdapter()
        runAdapter(pair, adapter)

        val client = cn.enaium.lsp.edit.dap.DapClient(pair.a)
        client.startListening()

        val caps = client.initialize(adapterID = "lsp-edit-test")
        assertTrue(caps.supportsConfigurationDoneRequest)

        client.setBreakpoints(Source(name = "demo.kt", path = "file:///demo.kt"), listOf(8, 10))
        assertEquals(setOf(8, 10), adapter.breakpointLines)

        client.continue_(threadId = 1)
        val frames = client.stackTrace(threadId = 1)
        assertEquals(1, frames.stackFrames.size)
        assertEquals(10, frames.stackFrames[0].line)
        assertEquals("file:///demo.kt", frames.stackFrames[0].source?.path)

        val scopes = client.scopes(frameId = 1)
        assertEquals("Locals", scopes.scopes[0].name)
        val vars = client.variables(variablesReference = 100)
        assertEquals(listOf("x", "y"), vars.variables.map { it.name })

        val eval = client.evaluate("x + y", frameId = 1)
        assertEquals("42", eval?.result)

        client.disconnect()
        pair.close()
    }
}