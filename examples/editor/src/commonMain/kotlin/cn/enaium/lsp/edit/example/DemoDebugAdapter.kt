package cn.enaium.lsp.edit.example

import cn.enaium.lsp.dap.DebugAdapter
import cn.enaium.lsp.dap.DebugClient
import cn.enaium.lsp.dap.DebugAdapterLauncher
import cn.enaium.lsp.dap.model.*
import cn.enaium.lsp.edit.lsp.InMemoryTransportPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An in-process demo debug adapter for the example. It pretends to execute
 * the sample Kotlin document: breakpoints pause "execution" at the requested
 * line, stepping moves through a fixed script of lines, and the stopped
 * state exposes stack frames with synthetic locals.
 */
class DemoDebugAdapter : DebugAdapter {

    /** Script lines visited by the simulated program (0-based doc lines). */
    private val script = listOf(13, 14, 15, 7, 8, 9, 10, 11, 16, 17)

    private var client: DebugClient? = null

    /** 1-based breakpoint lines. */
    private val breakpoints = mutableSetOf<Int>()

    private var pc = 0
    private var running = false
    private var entered = false

    /** Source path reported in stack frames. */
    var sourcePath: String = "file:///demo.kt"

    fun attachClient(c: DebugClient) {
        client = c
    }

    // ==================== DebugAdapter ====================

    override fun initialize(request: InitializeRequestArguments): Capabilities =
        Capabilities(supportsConfigurationDoneRequest = true)

    override fun configurationDone(): JsonElement? {
        entered = true
        return null
    }

    override fun launch(args: JsonElement?): JsonElement? {
        // Nothing real to launch: the script starts when configurationDone
        // arrives and the client asks to continue.
        return null
    }

    override fun disconnect(args: DisconnectArguments?): JsonElement? {
        running = false
        return null
    }

    override fun continue_(args: ContinueArguments): ContinueResponseBody {
        running = true
        runToNextBreakpoint()
        return ContinueResponseBody(allThreadsContinued = true)
    }

    override fun next(args: NextArguments): JsonElement? {
        step()
        return null
    }

    override fun stepIn(args: StepInArguments): JsonElement? {
        step()
        return null
    }

    override fun stepOut(args: StepOutArguments): JsonElement? {
        step()
        return null
    }

    override fun pause(args: PauseArguments): JsonElement? = null

    override fun setBreakpoints(args: SetBreakpointsArguments): SetBreakpointsResponseBody {
        breakpoints.clear()
        breakpoints.addAll(args.breakpoints?.map { it.line } ?: emptyList())
        return SetBreakpointsResponseBody(
            breakpoints = breakpoints.map { line ->
                Breakpoint(id = line, verified = true, line = line)
            },
        )
    }

    override fun threads(): ThreadsResponseBody =
        ThreadsResponseBody(listOf(Thread(id = 1, name = "main")))

    override fun stackTrace(args: StackTraceArguments): StackTraceResponseBody {
        val line = script.getOrElse(pc) { script.last() }
        return StackTraceResponseBody(
            stackFrames = listOf(
                StackFrame(
                    id = 1,
                    name = "main",
                    source = Source(name = "demo.kt", path = sourcePath),
                    line = line + 1,
                    column = 1,
                ),
            ),
        )
    }

    override fun scopes(args: ScopesArguments): ScopesResponseBody =
        ScopesResponseBody(
            listOf(
                Scope(
                    name = "Locals",
                    variablesReference = 100,
                    expensive = false,
                ),
            ),
        )

    override fun variables(args: VariablesArguments): VariablesResponseBody {
        val line = script.getOrElse(pc) { script.last() }
        return VariablesResponseBody(
            variables = listOf(
                Variable(name = "origin", value = "Point(0.0, 0.0)", type = "Point", variablesReference = 101),
                Variable(name = "target", value = "Point(3.0, 4.0)", type = "Point", variablesReference = 102),
                Variable(name = "distance", value = "5.0", type = "Double"),
                Variable(name = "line", value = "${line + 1}", type = "Int"),
            ),
        )
    }

    override fun evaluate(args: EvaluateArguments): EvaluateResponseBody =
        EvaluateResponseBody(result = "undefined", type = "String")

    override fun terminate(args: TerminateArguments?): JsonElement? {
        running = false
        return null
    }

    // ==================== Simulated execution ====================

    private fun step() {
        if (!entered) return
        pc++
        if (pc >= script.size) {
            running = false
            client?.sendEvent(DapEvent.Terminated)
            return
        }
        runToNextBreakpoint()
    }

    private fun runToNextBreakpoint() {
        if (!entered) return
        // Continue: advance until a breakpoint line (or the script end).
        while (pc < script.size) {
            val line = script[pc]
            if ((line + 1) in breakpoints) {
                stopAtBreakpoint()
                return
            }
            pc++
        }
        running = false
        client?.sendEvent(DapEvent.Terminated)
    }

    private fun stopAtBreakpoint() {
        running = false
        client?.sendEvent(
            DapEvent.Stopped,
            buildJsonObject {
                put("reason", "breakpoint")
                put("threadId", 1)
                put("allThreadsStopped", true)
            },
        )
    }
}

/** Runs [adapter] on a background coroutine, connected to the client side. */
fun runDemoDebugAdapter(
    pair: InMemoryTransportPair,
    adapter: DemoDebugAdapter,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        val launcher = DebugAdapterLauncher(pair.b, adapter)
        adapter.attachClient(launcher.client)
        launcher.listen()
    }
}
