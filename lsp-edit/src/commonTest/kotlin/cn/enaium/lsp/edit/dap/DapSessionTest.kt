package cn.enaium.lsp.edit.dap

import cn.enaium.lsp.dap.DebugAdapter
import cn.enaium.lsp.dap.DebugAdapterLauncher
import cn.enaium.lsp.dap.DebugClientLauncher
import cn.enaium.lsp.dap.model.*
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.Language
import cn.enaium.lsp.edit.lsp.InMemoryTransportPair
import cn.enaium.lsp.dap.DapException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A debug session driving a scripted adapter through the whole workflow: the
 * handshake, breakpoints of every kind, a stop that pulls stack/scopes/
 * variables and an exception explanation, variable editing, run-to-line,
 * restart/terminate, and the adapter's own requests to the client.
 */
class DapSessionTest {

    private class ScriptedAdapter : DebugAdapter {
        /** Set by the test: the adapter announces readiness through this. */
        var launcher: DebugAdapterLauncher? = null

        var breakpointLines: List<Int?> = emptyList()
        var exceptionFilters: List<String>? = null
        var configurationDone = false
        var continuedThread: Int? = null
        var gotoThread: Int? = null
        var gotoTarget: Int? = null
        var restarted = false
        var terminated = false
        var setVariableValue: String? = null
        var dataBreakpoints: List<DataBreakpoint> = emptyList()

        override fun initialize(request: InitializeRequestArguments): Capabilities = Capabilities(
                supportsConfigurationDoneRequest = true,
                supportsSetVariable = true,
                supportsExceptionInfoRequest = true,
                supportsGotoTargetsRequest = true,
                supportsRestartRequest = true,
                supportsTerminateRequest = true,
                supportsLoadedSourcesRequest = true,
                supportsDataBreakpoints = true,
                supportsCompletionsRequest = true,
                exceptionBreakpointFilters = listOf(
                    ExceptionBreakpointsFilter(filter = "uncaught", label = "Uncaught", default = true),
                    ExceptionBreakpointsFilter(filter = "caught", label = "Caught", default = false),
                ),
        )

        override fun configurationDone(): JsonElement? {
            configurationDone = true
            return null
        }

        override fun setBreakpoints(args: SetBreakpointsArguments): SetBreakpointsResponseBody {
            breakpointLines = args.breakpoints.orEmpty().map { it.line }
            return SetBreakpointsResponseBody(
                args.breakpoints.orEmpty().mapIndexed { i, bp ->
                    Breakpoint(id = i + 1, verified = bp.line != 99, line = bp.line, message = "no code at 99")
                },
            )
        }

        override fun setExceptionBreakpoints(args: SetExceptionBreakpointsArguments): SetBreakpointsResponseBody? {
            exceptionFilters = args.filters
            return null
        }

        override fun threads(): ThreadsResponseBody = ThreadsResponseBody(listOf(Thread(1, "main")))

        override fun stackTrace(args: StackTraceArguments): StackTraceResponseBody = StackTraceResponseBody(
            listOf(
                StackFrame(
                    id = 7,
                    name = "main",
                    source = Source(name = "demo.kt", path = "/demo.kt"),
                    line = 2,
                    column = 1,
                ),
            ),
        )

        override fun scopes(args: ScopesArguments): ScopesResponseBody =
            ScopesResponseBody(listOf(Scope(name = "Locals", variablesReference = 100)))

        override fun variables(args: VariablesArguments): VariablesResponseBody = VariablesResponseBody(
            listOf(Variable(name = "x", value = setVariableValue ?: "1", type = "Int")),
        )

        override fun setVariable(args: SetVariableArguments): SetVariableResponseBody {
            setVariableValue = args.value
            return SetVariableResponseBody(value = args.value, type = "Int")
        }

        override fun evaluate(args: EvaluateArguments): EvaluateResponseBody =
            EvaluateResponseBody(result = "1", type = "Int")

        override fun exceptionInfo(args: ExceptionInfoArguments): ExceptionInfoResponseBody =
            ExceptionInfoResponseBody(
                exceptionId = "IllegalStateException",
                description = "boom",
                breakMode = "always",
            )

        override fun continue_(args: ContinueArguments): ContinueResponseBody {
            continuedThread = args.threadId
            return ContinueResponseBody(allThreadsContinued = true)
        }

        override fun gotoTargets(args: GotoTargetsArguments): GotoTargetsResponseBody =
            GotoTargetsResponseBody(listOf(GotoTarget(id = 42, label = "line ${args.line}", line = args.line)))

        override fun goto_(args: GotoArguments): JsonElement? {
            gotoThread = args.threadId
            gotoTarget = args.targetId
            return null
        }

        override fun restart(args: RestartArguments?): JsonElement? {
            restarted = true
            return null
        }

        override fun terminate(args: TerminateArguments?): JsonElement? {
            terminated = true
            return null
        }

        override fun loadedSources(): LoadedSourcesResponseBody =
            LoadedSourcesResponseBody(listOf(Source(name = "demo.kt", path = "/demo.kt")))

        override fun modules(args: ModulesArguments?): ModulesResponseBody =
            ModulesResponseBody(listOf(Module(id = ModuleId.NumberValue(1), name = "demo")))

        override fun completions(args: CompletionsArguments): CompletionsResponseBody =
            CompletionsResponseBody(listOf(CompletionItem(label = "x", type = "variable")))

        override fun setDataBreakpoints(args: SetDataBreakpointsArguments): SetDataBreakpointsResponseBody {
            dataBreakpoints = args.breakpoints
            return SetDataBreakpointsResponseBody(listOf(Breakpoint(id = 1, verified = true)))
        }
    }

    @Test
    fun sessionDrivesTheWholeWorkflow() = runBlocking {
        val pair = InMemoryTransportPair()
        val adapter = ScriptedAdapter()
        val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapterLauncher = DebugAdapterLauncher(pair.b, adapter)
        adapter.launcher = adapterLauncher
        adapterScope.launch { adapterLauncher.listen() }

        val editor = Editor(initialText = "fun main() {\n    println(1)\n}", language = Language.kotlin)
        val client = DebugClientLauncher(pair.a)
        val session = DapSession(editor, client, sourceName = "demo.kt", sourcePath = "/demo.kt")
        try {
            session.start(breakpoints = listOf(SourceBreakpoint(line = 2)))
            assertEquals(listOf(2), adapter.breakpointLines)
            assertEquals(listOf("uncaught"), adapter.exceptionFilters, "the adapter's default filters are applied")
            assertTrue(adapter.configurationDone)
            assertTrue(session.active)
            assertTrue(session.running)

            // A stop: the session pulls the stack, scopes, variables and asks
            // the adapter to explain the exception.
            adapterLauncher.client.sendEvent(
                DapEvent.Stopped,
                cn.enaium.lsp.jsonrpc.JsonRpcJson.json.encodeToJsonElement(
                    StoppedEventBody.serializer(),
                    StoppedEventBody(reason = "exception", threadId = 1, allThreadsStopped = true),
                ),
            )
            // The host drains once per frame; the stop event arrives
            // asynchronously, so poll the way a frame loop would.
            await { session.drain(); session.stackFrames.isNotEmpty() && session.variables.isNotEmpty() }
            assertEquals(1, session.stoppedThreadId)
            assertEquals("exception", session.stopReason)
            assertEquals(7, session.stackFrames.first().id)
            assertEquals(listOf("x"), session.variables.map { it.name })
            assertEquals(2, editor.getExecutionLine(), "the stop line reaches the editor (1-based)")
            assertNotNull(session.exception, "an exception stop is explained")
            assertEquals("boom", session.exception?.description)

            // Variable editing refreshes the view with the new value.
            assertTrue(session.setVariable(100, "x", "42"))
            assertEquals("42", session.variables.first().value)

            // Run to a line: the adapter's jump target is used.
            assertTrue(session.runToLine(2))
            assertEquals(1, adapter.gotoThread)
            assertEquals(42, adapter.gotoTarget)

            // Breakpoints: unverified ones stay out of the editor gutter.
            session.setSourceBreakpoints(listOf(SourceBreakpoint(line = 5), SourceBreakpoint(line = 99)))
            assertEquals(setOf(5), session.getBreakpoints())
            assertEquals(2, session.breakpoints.size, "unverified breakpoints are still reported")

            session.setExceptionBreakpoints(listOf("caught"))
            assertEquals(listOf("caught"), adapter.exceptionFilters)

            session.setDataBreakpoints(listOf(DataBreakpoint(dataId = "x", accessType = "write")))
            assertEquals(1, adapter.dataBreakpoints.size)

            session.loadSources()
            assertEquals(listOf("/demo.kt"), session.loadedSources.mapNotNull { it.path })
            session.loadModules()
            assertEquals(listOf("demo"), session.modules.map { it.name })
            assertNotNull(session.consoleCompletions("x", 1), "the debug console completes")

            session.restart()
            assertTrue(adapter.restarted)
            session.terminate()
            assertTrue(adapter.terminated)

            // Adapter -> client requests are answered, never ignored.
            assertFailsWith<DapException> {
                adapterLauncher.runInTerminal(RunInTerminalArguments(args = listOf("echo", "hi")))
            }
            session.onRunInTerminal { RunInTerminalResponseBody(processId = 1234) }
            assertEquals(
                1234,
                adapterLauncher.runInTerminal(RunInTerminalArguments(args = listOf("echo", "hi"))).processId,
            )
        } finally {
            session.close()
            adapterScope.cancel()
            pair.close()
        }
    }

    /** Polls [condition] on the test thread; the session works on its own scope. */
    private suspend fun await(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }
}
