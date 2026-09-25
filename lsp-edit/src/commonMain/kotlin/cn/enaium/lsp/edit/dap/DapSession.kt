package cn.enaium.lsp.edit.dap

import cn.enaium.lsp.dap.DebugClientLauncher
import cn.enaium.lsp.dap.model.*
import cn.enaium.lsp.edit.DocPos
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.jsonrpc.JsonRpcJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * A debug session: binds an [Editor] to a [DebugClientLauncher] and drives a
 * debug adapter through the whole DAP workflow — the handshake (launch or
 * attach), every breakpoint kind, execution control (including reverse
 * debugging and run-to-line), stack/scope/variable inspection with variable
 * editing, exception details, loaded sources and modules, the debug console —
 * and keeps the state a debug panel renders.
 *
 * The requests the adapter sends the other way (`runInTerminal`,
 * `startDebugging`) are answered here as well: an adapter that asks and never
 * hears back stops where it is, so the session always responds, declining
 * unless the host registered a handler for that request.
 *
 * Adapter round trips run on the session's coroutine scope; the state the UI
 * reads is applied on the render thread by [drain], which the host must call
 * once per frame before rendering the debug UI.
 */
class DapSession(
    val editor: Editor,
    val client: DebugClientLauncher,
    val sourceName: String,
    val sourcePath: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    // ==================== Session state ====================

    /** True once the adapter has been initialized and a session is active. */
    var active: Boolean = false
        private set

    /** True while the program is running (between stops). */
    var running: Boolean = false
        private set

    /** The thread the program stopped in (null when running). */
    var stoppedThreadId: Int? = null
        private set

    /** Stack frames of the stopped thread (top frame first). */
    var stackFrames: List<StackFrame> = emptyList()
        private set

    /** Scopes of the selected frame. */
    var scopes: List<Scope> = emptyList()
        private set

    /** Variables of the selected scope (flattened one level). */
    var variables: List<Variable> = emptyList()
        private set

    /** The frame whose scopes/variables are shown (0 = top of stack). */
    var selectedFrame: Int = 0
        private set

    /** Diagnostic output lines from the adapter. */
    val output: MutableList<String> = mutableListOf()

    /**
     * Why `launch`/`attach` failed, when it did. The response arrives only
     * after the debuggee starts, so a failure surfaces here instead of
     * aborting the handshake.
     */
    var launchError: String? = null
        private set

    /** Last stop reason (e.g. "breakpoint", "step", "entry"). */
    var stopReason: String = ""
        private set

    /** The whole `stopped` body: hit breakpoints, all-threads flag, text. */
    var lastStop: StoppedEventBody? = null
        private set

    /** Threads the adapter has reported (kept fresh by the `thread` event). */
    var threads: List<Thread> = emptyList()
        private set

    /** The adapter's verdicts for the breakpoints it was given. */
    var breakpoints: List<Breakpoint> = emptyList()
        private set

    /** Why the program stopped, when the adapter can explain an exception. */
    var exception: ExceptionInfoResponseBody? = null
        private set

    /** Sources the adapter has loaded (also fed by `loadedSource` events). */
    var loadedSources: List<Source> = emptyList()
        private set

    /** Modules the adapter has loaded (also fed by `module` events). */
    var modules: List<Module> = emptyList()
        private set

    /** Exit code of the debuggee, once it exited. */
    var exitCode: Int? = null
        private set

    /** The debuggee process, when the adapter announced one. */
    var process: ProcessEventBody? = null
        private set

    /**
     * The adapter's capabilities: the `initialize` result, replaced when the
     * adapter announces new ones through the `capabilities` event.
     */
    var capabilities: Capabilities? = null
        private set

    /** Breakpoint lines the adapter verified, for the editor gutter. */
    private var verifiedLines: Set<Int> = emptySet()

    /** True when a stopped event has been delivered but not yet drained. */
    private var pendingStop = false

    /** True when the adapter invalidated cached state (refetch on next drain). */
    private var pendingInvalidated = false

    private val callbacks = Callbacks()

    /** Host hooks; all of them run on the render thread. */
    class Callbacks {
        /** Invoked when the stop position changes. */
        var onStopped: ((DocPos?) -> Unit)? = null

        /** Invoked when the session starts or ends. */
        var onSessionChanged: (() -> Unit)? = null

        /**
         * Every adapter event, after the session applied its own state change:
         * (event name, raw body). This is how events the session does not model
         * (progress, memory, …) still reach a host.
         */
        var onAdapterEvent: ((String, JsonElement?) -> Unit)? = null

        /** Answers `runInTerminal`; null declines the request. */
        var onRunInTerminal: ((RunInTerminalArguments) -> RunInTerminalResponseBody?)? = null

        /** Answers `startDebugging`; null declines the request. */
        var onStartDebugging: ((StartDebuggingArguments) -> Boolean)? = null
    }

    fun onStopped(handler: (DocPos?) -> Unit) {
        callbacks.onStopped = handler
    }

    fun onSessionChanged(handler: () -> Unit) {
        callbacks.onSessionChanged = handler
    }

    /** Registers the hook receiving every adapter event (see [Callbacks]). */
    fun onAdapterEvent(handler: (String, JsonElement?) -> Unit) {
        callbacks.onAdapterEvent = handler
    }

    /** Registers the handler answering `runInTerminal` (a null return declines). */
    fun onRunInTerminal(handler: (RunInTerminalArguments) -> RunInTerminalResponseBody?) {
        callbacks.onRunInTerminal = handler
    }

    /** Registers the handler answering `startDebugging` (false declines). */
    fun onStartDebugging(handler: (StartDebuggingArguments) -> Boolean) {
        callbacks.onStartDebugging = handler
    }

    // ==================== Session lifecycle ====================

    /**
     * Initializes the adapter, sends [breakpoints] and the exception
     * breakpoints the adapter defaults to, launches and signals configuration
     * done.
     *
     * The order matters: `configurationDone` is what starts the debuggee, so
     * breakpoints have to reach the adapter before it — a breakpoint sent
     * afterwards arrives once the program has already run past it.
     */
    suspend fun start(
        breakpoints: List<SourceBreakpoint> = emptyList(),
        launchArgs: JsonElement? = null,
    ) {
        beginSession { client.send(DapCommands.Launch, launchArgs) }
        finishConfiguration(breakpoints)
    }

    /**
     * Like [start], but attaches to an already running process instead of
     * launching one.
     */
    suspend fun attach(
        breakpoints: List<SourceBreakpoint> = emptyList(),
        attachArgs: JsonElement? = null,
    ) {
        beginSession { client.send(DapCommands.Attach, attachArgs) }
        finishConfiguration(breakpoints)
    }

    private suspend fun beginSession(launchOrAttach: () -> kotlinx.coroutines.Deferred<JsonElement?>) {
        // The listen loop has to run while requests are in flight; the session
        // owns the scope, so closing it stops the loop too.
        scope.launch { client.listen() }
        wireEvents()
        capabilities = client.initialize(InitializeRequestArguments(adapterID = "lsp-edit"))
        // launch/attach comes before the breakpoints: adapters such as debugpy
        // create their session there, and only then announce `initialized`.
        // Its response arrives only once the debuggee runs, so it is not
        // awaited here — that would deadlock the handshake (the debuggee starts
        // on configurationDone, which comes later). VS Code does the same.
        val launch = launchOrAttach()
        launch.invokeOnCompletion { error ->
            if (error != null) launchError = error.message ?: error.toString()
        }
        // `initialized` is the signal that breakpoint requests are accepted —
        // sent earlier they are rejected outright ("Server is not available").
        // A minimal adapter that never sends one must not block the session.
        client.awaitInitialized()
        active = true
        running = false
        exitCode = null
        exception = null
        launchError = null
        lastStop = null
        breakpoints = emptyList()
        verifiedLines = emptySet()
    }

    private suspend fun finishConfiguration(breakpoints: List<SourceBreakpoint>) {
        if (breakpoints.isNotEmpty()) setSourceBreakpoints(breakpoints)
        applyDefaultExceptionBreakpoints()
        client.configurationDone()
        running = true
        callbacks.onSessionChanged?.invoke()
    }

    /** Ends the session and disconnects from the adapter. */
    suspend fun stop() {
        if (!active) return
        try {
            client.disconnect(DisconnectArguments(terminateDebuggee = true))
        } catch (_: Exception) {
        }
        active = false
        running = false
        stoppedThreadId = null
        stackFrames = emptyList()
        scopes = emptyList()
        variables = emptyList()
        exception = null
        editor.setExecutionLine(null)
        callbacks.onSessionChanged?.invoke()
    }

    /**
     * The `restart` request: the adapter restarts the debuggee. Nothing to do
     * if the adapter does not support it.
     */
    suspend fun restart() {
        if (capabilities?.supportsRestartRequest != true) return
        client.restart()
        running = true
        stoppedThreadId = null
        stackFrames = emptyList()
        scopes = emptyList()
        variables = emptyList()
        exception = null
        editor.setExecutionLine(null)
        callbacks.onSessionChanged?.invoke()
    }

    /** The `terminate` request: end the debuggee, keep the session usable. */
    suspend fun terminate() {
        if (capabilities?.supportsTerminateRequest != true) return
        client.terminate()
        running = false
        callbacks.onSessionChanged?.invoke()
    }

    /** The `terminateThreads` request. */
    suspend fun terminateThreads(threadIds: List<Int>) {
        if (capabilities?.supportsTerminateThreadsRequest != true) return
        client.terminateThreads(threadIds)
    }

    override fun close() {
        scope.cancel()
    }

    // ==================== Breakpoints ====================

    /** Replaces the breakpoint set on the adapter for this session's source. */
    suspend fun applyBreakpoints(lines: Set<Int>) {
        setSourceBreakpoints(lines.sorted().map { SourceBreakpoint(line = it) })
    }

    /** The adapter's verdict for a line, if it judged one there. */
    fun breakpointAt(line: Int): Breakpoint? = breakpoints.firstOrNull { it.line == line }

    /**
     * Sets source breakpoints, with the conditions, hit counts and log messages
     * the adapter supports. The adapter's verdicts land in [breakpoints]; the
     * verified lines are pushed to the editor gutter.
     */
    suspend fun setSourceBreakpoints(breakpoints: List<SourceBreakpoint>) {
        if (!active) return
        val response = client.setBreakpoints(
            Source(name = sourceName, path = sourcePath),
            breakpoints,
        )
        this.breakpoints = response.breakpoints
        verifiedLines = response.breakpoints
            .filter { it.verified && it.line != null }
            .mapNotNull { it.line }
            .toSet()
        editor.setBreakpoints(verifiedLines)
    }

    /** The `setFunctionBreakpoints` request. */
    suspend fun setFunctionBreakpoints(breakpoints: List<FunctionBreakpoint>) {
        if (capabilities?.supportsFunctionBreakpoints != true) return
        val response = client.setFunctionBreakpoints(breakpoints)
        if (response != null) this.breakpoints = response.breakpoints
    }

    /**
     * The `setExceptionBreakpoints` request. [filters] default to the ones the
     * adapter marks as on by default.
     */
    suspend fun setExceptionBreakpoints(filters: List<String> = defaultExceptionFilters()) {
        if (filters.isEmpty()) return
        client.setExceptionBreakpoints(filters)
    }

    private fun defaultExceptionFilters(): List<String> =
        capabilities?.exceptionBreakpointFilters.orEmpty().filter { it.default }.map { it.filter }

    private suspend fun applyDefaultExceptionBreakpoints() {
        val filters = defaultExceptionFilters()
        if (filters.isNotEmpty()) client.setExceptionBreakpoints(filters)
    }

    /** The `dataBreakpointInfo` request: can [name] be watched? */
    suspend fun dataBreakpointInfo(name: String, variablesReference: Int? = null): DataBreakpointInfoResponseBody? {
        if (capabilities?.supportsDataBreakpoints != true) return null
        return client.dataBreakpointInfo(name, variablesReference, stackFrames.getOrNull(selectedFrame)?.id)
    }

    /** The `setDataBreakpoints` request (watchpoints). */
    suspend fun setDataBreakpoints(breakpoints: List<DataBreakpoint>) {
        if (capabilities?.supportsDataBreakpoints != true) return
        val response = client.setDataBreakpoints(breakpoints)
        if (response != null) this.breakpoints = response.breakpoints
    }

    /** The lines the adapter reported as verified breakpoints. */
    fun getBreakpoints(): Set<Int> = verifiedLines

    // ==================== Execution control ====================

    suspend fun continue_() {
        val thread = stoppedThreadId ?: return
        client.continue_(thread)
        running = true
        stoppedThreadId = null
        clearStop()
    }

    suspend fun next() = step { client.next(it) }

    suspend fun stepIn() = step { client.stepIn(it) }

    suspend fun stepOut() = step { client.stepOut(it) }

    /** The `stepBack` request (reverse debugging). */
    suspend fun stepBack() = step { client.stepBack(it) }

    /** The `reverseContinue` request (reverse debugging). */
    suspend fun reverseContinue() {
        val thread = stoppedThreadId ?: return
        client.reverseContinue(thread)
        running = true
        stoppedThreadId = null
        clearStop()
    }

    private suspend fun step(action: suspend (Int) -> Unit) {
        val thread = stoppedThreadId ?: return
        action(thread)
        running = true
        stoppedThreadId = null
        clearStop()
    }

    /**
     * The `pause` request. While the program runs there is no stopped thread,
     * so this uses the thread of the last stop (or the first the adapter
     * reported) — otherwise pausing a running program could never work.
     */
    suspend fun pause() {
        val thread = stoppedThreadId ?: lastStop?.threadId ?: threads.firstOrNull()?.id ?: return
        client.pause(thread)
    }

    /**
     * Runs to [line] (1-based, in this session's source): asks the adapter for
     * the jump targets on that line and jumps to the one it offers.
     */
    suspend fun runToLine(line: Int): Boolean {
        val thread = stoppedThreadId ?: return false
        if (capabilities?.supportsGotoTargetsRequest != true) return false
        val targets = client.gotoTargets(Source(name = sourceName, path = sourcePath), line)?.targets.orEmpty()
        val target = targets.firstOrNull { it.line == line } ?: targets.firstOrNull() ?: return false
        client.goto_(thread, target.id)
        running = true
        stoppedThreadId = null
        clearStop()
        return true
    }

    private fun clearStop() {
        stackFrames = emptyList()
        scopes = emptyList()
        variables = emptyList()
        exception = null
        editor.setExecutionLine(null)
    }

    // ==================== Stack / variables ====================

    /** Selects stack frame [index] and fetches its scopes + variables. */
    fun selectFrame(index: Int) {
        if (index !in stackFrames.indices) return
        selectedFrame = index
        scope.launch {
            refreshScopesAndVariables(index)
        }
    }

    private suspend fun refreshScopesAndVariables(frameIndex: Int) {
        val frame = stackFrames.getOrNull(frameIndex) ?: return
        val scopeResult = try {
            client.scopes(frame.id)
        } catch (_: Exception) {
            return
        }
        scopes = scopeResult.scopes
        val first = scopeResult.scopes.firstOrNull()
        if (first != null && first.variablesReference != 0) {
            variables = try {
                client.variables(first.variablesReference).variables
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            variables = emptyList()
        }
    }

    /** Refreshes the variables of the first scope of the selected frame. */
    suspend fun refreshVariables() {
        refreshScopesAndVariables(selectedFrame)
    }

    /** The `variables` request for a structured variable (expand a node). */
    suspend fun variablesOf(variablesReference: Int): List<Variable> = try {
        client.variables(variablesReference).variables
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The `setVariable` request: assigns to a variable and refreshes the view.
     * Returns false when the adapter does not support it.
     */
    suspend fun setVariable(variablesReference: Int, name: String, value: String): Boolean {
        if (capabilities?.supportsSetVariable != true) return false
        val response = client.setVariable(variablesReference, name, value) ?: return false
        refreshVariables()
        return response.value.isNotEmpty()
    }

    /** The `setExpression` request: assigns to an expression in a frame. */
    suspend fun setExpression(expression: String, value: String): Boolean {
        if (capabilities?.supportsSetExpression != true) return false
        val frameId = stackFrames.getOrNull(selectedFrame)?.id
        val response = client.setExpression(expression, value, frameId) ?: return false
        refreshVariables()
        return response.value.isNotEmpty()
    }

    /** The `evaluate` request in the selected frame (watch, hover, REPL). */
    suspend fun evaluate(expression: String): EvaluateResponseBody? {
        val frameId = stackFrames.getOrNull(selectedFrame)?.id
        return try {
            client.evaluate(expression, frameId)
        } catch (_: Exception) {
            null
        }
    }

    /** The `exceptionInfo` request for the stopped thread. */
    suspend fun refreshExceptionInfo() {
        if (capabilities?.supportsExceptionInfoRequest != true) return
        val thread = stoppedThreadId ?: return
        exception = try {
            client.exceptionInfo(thread)
        } catch (_: Exception) {
            null
        }
    }

    /** The `loadedSources` request. */
    suspend fun loadSources() {
        if (capabilities?.supportsLoadedSourcesRequest != true) return
        loadedSources = client.loadedSources()?.sources.orEmpty()
    }

    /** The `modules` request. */
    suspend fun loadModules() {
        modules = client.modules()?.modules.orEmpty()
    }

    /** The `source` request: the text behind a `sourceReference`. */
    suspend fun sourceContent(sourceReference: Int): SourceResponseBody? = try {
        client.source(sourceReference)
    } catch (_: Exception) {
        null
    }

    /** The `completions` request for the debug console. */
    suspend fun consoleCompletions(text: String, column: Int): CompletionsResponseBody? {
        if (capabilities?.supportsCompletionsRequest != true) return null
        val frameId = stackFrames.getOrNull(selectedFrame)?.id
        return try {
            client.completions(text, column, frameId)
        } catch (_: Exception) {
            null
        }
    }

    // ==================== Events ====================

    /**
     * Wires the adapter events. Registered through the raw hook: several of
     * them (`terminated`, `exited`) carry a body only sometimes, and the typed
     * registration would drop those.
     */
    private fun wireEvents() {
        on(DapEvent.Stopped, StoppedEventBody.serializer()) { body ->
            lastStop = body
            pendingStop = true
            stopReason = body?.reason ?: "unknown"
            if (body?.allThreadsStopped == true) {
                running = false
            }
        }
        on(DapEvent.Continued, ContinuedEventBody.serializer()) { body ->
            if (body == null || body.allThreadsContinued != false || body.threadId == stoppedThreadId) {
                running = true
                stoppedThreadId = null
                clearStop()
            }
        }
        on(DapEvent.Exited, ExitedEventBody.serializer()) { body ->
            exitCode = body?.exitCode
            running = false
            stoppedThreadId = null
        }
        on(DapEvent.Terminated, TerminatedEventBody.serializer()) {
            active = false
            running = false
            stoppedThreadId = null
            clearStop()
            callbacks.onSessionChanged?.invoke()
        }
        on(DapEvent.Thread, ThreadEventBody.serializer()) { body ->
            if (body != null) {
                threads = when (body.reason) {
                    "started" -> threads.filterNot { it.id == body.threadId } + Thread(body.threadId, "thread ${body.threadId}")
                    "exited" -> threads.filterNot { it.id == body.threadId }
                    else -> threads
                }
            }
        }
        on(DapEvent.Output, OutputEventBody.serializer()) { body ->
            body?.output?.let { line ->
                output.add(line.trimEnd())
                if (output.size > 500) output.removeAt(0)
            }
        }
        on(DapEvent.Breakpoint, BreakpointEventBody.serializer()) { body ->
            val changed = body?.breakpoint ?: return@on
            breakpoints = breakpoints.map { if (it.id != null && it.id == changed.id) changed else it }
            // The line is a public API property, so it needs an explicit
            // unwrap before it can join the gutter's set.
            if (changed.verified) {
                changed.line?.let { line ->
                    verifiedLines = verifiedLines + line
                    editor.setBreakpoints(verifiedLines)
                }
            }
        }
        on(DapEvent.Module, ModuleEventBody.serializer()) { body ->
            val changed = body?.module ?: return@on
            modules = modules.filterNot { it.id == changed.id } + changed
        }
        on(DapEvent.LoadedSource, LoadedSourceEventBody.serializer()) { body ->
            val changed = body?.source ?: return@on
            loadedSources = when (body.reason) {
                "removed" -> loadedSources.filterNot { it.path == changed.path }
                else -> loadedSources.filterNot { it.path == changed.path } + changed
            }
        }
        on(DapEvent.Process, ProcessEventBody.serializer()) { body ->
            process = body
        }
        on(DapEvent.Capabilities, CapabilitiesEventBody.serializer()) { body ->
            body?.capabilities?.let { capabilities = it }
        }
        on(DapEvent.Invalidated, InvalidatedEventBody.serializer()) { body ->
            // Cached stack/variables are stale; the next drain refetches them.
            val areas = body?.areas
            if (areas == null || InvalidatedAreas.All in areas ||
                InvalidatedAreas.Stacks in areas || InvalidatedAreas.Variables in areas
            ) {
                pendingInvalidated = true
            }
        }
        // Events the session keeps no state for still reach the host hook.
        for (event in PASS_THROUGH_EVENTS) {
            client.onEventRaw(event) { body -> callbacks.onAdapterEvent?.invoke(event, body) }
        }
        wireAdapterRequests()
    }

    /**
     * Answers the requests an adapter sends the client. Without a response the
     * adapter waits forever, so an unhandled request is declined explicitly
     * rather than ignored.
     */
    private fun wireAdapterRequests() {
        client.onRequest(DapCommands.RunInTerminal) { params ->
            val handler = callbacks.onRunInTerminal
                ?: error("runInTerminal is not supported by this client")
            val args = params?.let { JsonRpcJson.json.decodeFromJsonElement(RunInTerminalArguments.serializer(), it) }
            val response = handler(args ?: RunInTerminalArguments())
                ?: error("runInTerminal was declined")
            JsonRpcJson.json.encodeToJsonElement(RunInTerminalResponseBody.serializer(), response)
        }
        client.onRequest(DapCommands.StartDebugging) { params ->
            val handler = callbacks.onStartDebugging
                ?: error("startDebugging is not supported by this client")
            val args = params?.let { JsonRpcJson.json.decodeFromJsonElement(StartDebuggingArguments.serializer(), it) }
                ?: error("startDebugging needs a configuration")
            if (!handler(args)) error("startDebugging was declined")
            JsonNull
        }
    }

    /** Registers one event: typed for the session's state, raw for the host. */
    private fun <T> on(event: String, serializer: KSerializer<T>, handle: (T?) -> Unit) {
        client.onEventRaw(event) { raw ->
            val body = if (raw == null || raw is JsonNull) {
                null
            } else {
                JsonRpcJson.json.decodeFromJsonElement(serializer, raw)
            }
            handle(body)
            callbacks.onAdapterEvent?.invoke(event, raw)
        }
    }

    /**
     * Applies pending adapter state on the render thread. Call once per frame
     * before rendering the debug UI.
     */
    fun drain() {
        if (!pendingStop && !pendingInvalidated) return
        val stopped = pendingStop
        pendingStop = false
        pendingInvalidated = false
        scope.launch {
            if (!stopped) {
                // The adapter invalidated cached state; refetch what is shown.
                if (stackFrames.isNotEmpty()) refreshScopesAndVariables(selectedFrame)
                return@launch
            }
            val known = try {
                client.threads().threads
            } catch (_: Exception) {
                emptyList()
            }
            if (known.isNotEmpty()) threads = known
            val threadId = lastStop?.threadId ?: known.firstOrNull()?.id ?: 0
            stoppedThreadId = threadId
            running = false
            val frames = try {
                client.stackTrace(threadId).stackFrames
            } catch (_: Exception) {
                emptyList()
            }
            stackFrames = frames
            selectedFrame = 0
            scopes = emptyList()
            variables = emptyList()
            exception = null

            val top = frames.firstOrNull()
            val pos = if (top != null && top.source?.path == sourcePath) {
                DocPos(top.line - 1, 0)
            } else null
            if (pos != null) {
                // The editor takes the execution line 1-based (it is a line
                // number, not a row), while DocPos is 0-based — passing the
                // row here highlighted the line above the stop.
                editor.setExecutionLine(top!!.line)
                editor.unfoldAround(pos.line)
            }
            refreshScopesAndVariables(0)
            // An exception stop is worth explaining: ask the adapter why.
            if (lastStop?.reason == "exception") refreshExceptionInfo()
            callbacks.onStopped?.invoke(pos)
        }
    }

    private companion object {
        /**
         * Events with no state of their own here: progress, memory, and the
         * rest of the standard set. They are forwarded to the host hook.
         */
        val PASS_THROUGH_EVENTS = listOf(
            DapEvent.ProgressStart,
            DapEvent.ProgressUpdate,
            DapEvent.ProgressEnd,
            DapEvent.Memory,
        )
    }
}
