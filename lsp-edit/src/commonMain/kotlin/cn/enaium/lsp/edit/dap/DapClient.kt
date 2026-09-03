package cn.enaium.lsp.edit.dap

import cn.enaium.lsp.dap.model.*
import cn.enaium.lsp.jsonrpc.JsonRpcLauncher
import cn.enaium.lsp.jsonrpc.MessageTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A Debug Adapter Protocol client built on lsp-kmp's [JsonRpcLauncher].
 *
 * Mirrors [cn.enaium.lsp.dap.DebugAdapter] from the adapter side: this is
 * the client that sends requests and receives events. Create with [connect],
 * run [startListening], then drive a session with [initialize], [launch],
 * breakpoints and stepping commands.
 */
class DapClient(
    val transport: MessageTransport,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    private val launcher = JsonRpcLauncher(transport)

    /** The adapter's capabilities, populated after [initialize]. */
    var capabilities: Capabilities? = null
        private set

    /** Starts the receive loop on a background coroutine. */
    fun startListening() {
        scope.launch {
            launcher.listen()
        }
    }

    // ==================== Requests ====================

    /** The `initialize` request. Must be the first request sent. */
    suspend fun initialize(adapterID: String = "lsp-edit"): Capabilities {
        val caps = launcher.request(
            DapCommands.Initialize,
            InitializeRequestArguments(adapterID = adapterID),
            InitializeRequestArguments.serializer(),
            Capabilities.serializer(),
        )
        capabilities = caps
        return caps
    }

    /** The `launch` request with raw arguments. */
    suspend fun launch(args: JsonElement? = null): JsonElement? = rawRequest(DapCommands.Launch, args)

    /** The `configurationDone` request (start running after setup). */
    suspend fun configurationDone(): JsonElement? = rawRequest(DapCommands.ConfigurationDone, null)

    /** Sets source breakpoints; returns the adapter's verified breakpoints. */
    suspend fun setBreakpoints(
        source: Source,
        lines: List<Int>,
    ): SetBreakpointsResponseBody = launcher.request(
        DapCommands.SetBreakpoints,
        SetBreakpointsArguments(
            source = source,
            breakpoints = lines.map { SourceBreakpoint(line = it) },
        ),
        SetBreakpointsArguments.serializer(),
        SetBreakpointsResponseBody.serializer(),
    )

    /** The `continue` request. */
    suspend fun continue_(threadId: Int): ContinueResponseBody = launcher.request(
        DapCommands.Continue,
        ContinueArguments(threadId),
        ContinueArguments.serializer(),
        ContinueResponseBody.serializer(),
    )

    /** The `next` (step over) request. */
    suspend fun next(threadId: Int): JsonElement? = rawRequest(DapCommands.Next, threadIdJson(threadId))

    /** The `stepIn` request. */
    suspend fun stepIn(threadId: Int): JsonElement? = rawRequest(DapCommands.StepIn, threadIdJson(threadId))

    /** The `stepOut` request. */
    suspend fun stepOut(threadId: Int): JsonElement? = rawRequest(DapCommands.StepOut, threadIdJson(threadId))

    /** The `pause` request. */
    suspend fun pause(threadId: Int): JsonElement? = rawRequest(DapCommands.Pause, threadIdJson(threadId))

    /** The `threads` request. */
    suspend fun threads(): ThreadsResponseBody = launcher.request(
        DapCommands.Threads,
        ThreadsResponseBody.serializer(),
    )

    /** The `stackTrace` request for [threadId]. */
    suspend fun stackTrace(threadId: Int): StackTraceResponseBody = launcher.request(
        DapCommands.StackTrace,
        StackTraceArguments(threadId = threadId),
        StackTraceArguments.serializer(),
        StackTraceResponseBody.serializer(),
    )

    /** The `scopes` request for a stack frame. */
    suspend fun scopes(frameId: Int): ScopesResponseBody = launcher.request(
        DapCommands.Scopes,
        ScopesArguments(frameId),
        ScopesArguments.serializer(),
        ScopesResponseBody.serializer(),
    )

    /** The `variables` request for a scope or variable reference. */
    suspend fun variables(variablesReference: Int): VariablesResponseBody = launcher.request(
        DapCommands.Variables,
        VariablesArguments(variablesReference),
        VariablesArguments.serializer(),
        VariablesResponseBody.serializer(),
    )

    /** The `evaluate` request (e.g. watch/hover expressions). */
    suspend fun evaluate(expression: String, frameId: Int? = null): EvaluateResponseBody? =
        try {
            launcher.request(
                DapCommands.Evaluate,
                EvaluateArguments(expression = expression, frameId = frameId),
                EvaluateArguments.serializer(),
                EvaluateResponseBody.serializer(),
            )
        } catch (_: Exception) {
            null
        }

    /** The `disconnect` request (end the debug session). */
    suspend fun disconnect(): JsonElement? = rawRequest(DapCommands.Disconnect, null)

    override fun close() {
        scope.cancel()
    }

    // ==================== Events (adapter -> client) ====================

    /**
     * Registers a handler for an adapter event (e.g. `stopped`, `continued`,
     * `terminated`, `output`), decoded as [T] (or null for no body).
     * Called on the launcher's listen thread — marshal to the UI thread.
     */
    fun <T> onEvent(event: String, bodySerializer: KSerializer<T>?, handler: (T?) -> Unit) {
        if (bodySerializer == null) {
            launcher.onNotification(event) { handler(null) }
        } else {
            launcher.onNotification(event, bodySerializer) { handler(it) }
        }
    }

    /** Registers a handler for an event with no body (e.g. `terminated`). */
    fun onEvent(event: String, handler: () -> Unit) {
        launcher.onNotification(event) { handler() }
    }

    // ==================== Internal ====================

    private suspend fun rawRequest(method: String, params: JsonElement?): JsonElement? =
        try {
            launcher.request(method, params, JsonElement.serializer(), JsonElement.serializer())
        } catch (_: Exception) {
            null
        }

    private fun threadIdJson(threadId: Int): JsonObject =
        buildJsonObject {
            put("threadId", JsonPrimitive(threadId))
        }
}
