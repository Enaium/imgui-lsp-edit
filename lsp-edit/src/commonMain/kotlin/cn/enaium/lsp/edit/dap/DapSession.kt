package cn.enaium.lsp.edit.dap

import cn.enaium.lsp.dap.model.*
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.DocPos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * A debug session: binds an [Editor] widget to a [DapClient] (a debug
 * adapter), tracks breakpoints, the stopped state, stack frames, scopes and
 * variables, and exposes the UI state for a debug panel.
 *
 * All adapter round trips run on the session's coroutine scope; results are
 * marshalled back onto the render thread via [drain], which the host must
 * call every frame before rendering the debug UI.
 */
class DapSession(
    val editor: Editor,
    val client: DapClient,
    val sourceName: String,
    val sourcePath: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

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

    /** Breakpoint lines currently known to be set on the adapter. */
    private var breakpointLines: Set<Int> = emptySet()

    /** True when a stopped event has been delivered but not yet drained. */
    private var pendingStop = false

    private val callbacks = Callbacks()

    class Callbacks {
        /** Invoked on the render thread when the stop position changes. */
        var onStopped: ((DocPos?) -> Unit)? = null

        /** Invoked on the render thread when the session starts/ends. */
        var onSessionChanged: (() -> Unit)? = null
    }

    fun onStopped(handler: (DocPos?) -> Unit) {
        callbacks.onStopped = handler
    }

    fun onSessionChanged(handler: () -> Unit) {
        callbacks.onSessionChanged = handler
    }

    // ==================== Session lifecycle ====================

    /**
     * Initializes the adapter, applies [breakpoints], launches and signals
     * configuration done. Returns the adapter capabilities.
     */
    suspend fun start(breakpoints: Set<Int> = emptySet(), launchArgs: JsonElement? = null) {
        client.startListening()
        client.initialize(adapterID = "lsp-edit")
        active = true
        running = false

        applyBreakpoints(breakpoints)

        client.launch(launchArgs)
        client.configurationDone()
        running = true
        callbacks.onSessionChanged?.invoke()
    }

    /** Ends the session and disconnects from the adapter. */
    suspend fun stop() {
        if (!active) return
        try {
            client.disconnect()
        } catch (_: Exception) {
        }
        active = false
        running = false
        stoppedThreadId = null
        stackFrames = emptyList()
        scopes = emptyList()
        variables = emptyList()
        editor.setExecutionLine(null)
        callbacks.onSessionChanged?.invoke()
    }

    override fun close() {
        scope.cancel()
    }

    // ==================== Breakpoints ====================

    /** Replaces the breakpoint set on the adapter for this session's source. */
    suspend fun applyBreakpoints(lines: Set<Int>) {
        if (!active) return
        val response = client.setBreakpoints(
            Source(name = sourceName, path = sourcePath),
            lines.sorted(),
        )
        breakpointLines = response.breakpoints
            .filter { it.verified && it.line != null }
            .map { it.line!! }
            .toSet()
        // Reflect the adapter's verdict back on the editor gutter.
        editor.setBreakpoints(breakpointLines)
    }

    /** The lines the adapter reported as verified breakpoints. */
    fun getBreakpoints(): Set<Int> = breakpointLines

    // ==================== Execution control ====================

    suspend fun continue_() {
        val thread = stoppedThreadId ?: return
        client.continue_(thread)
        running = true
        stoppedThreadId = null
        stackFrames = emptyList()
        scopes = emptyList()
        variables = emptyList()
        editor.setExecutionLine(null)
    }

    suspend fun next() {
        val thread = stoppedThreadId ?: return
        client.next(thread)
        running = true
        stoppedThreadId = null
        editor.setExecutionLine(null)
    }

    suspend fun stepIn() {
        val thread = stoppedThreadId ?: return
        client.stepIn(thread)
        running = true
        stoppedThreadId = null
        editor.setExecutionLine(null)
    }

    suspend fun stepOut() {
        val thread = stoppedThreadId ?: return
        client.stepOut(thread)
        running = true
        stoppedThreadId = null
        editor.setExecutionLine(null)
    }

    suspend fun pause() {
        val thread = stoppedThreadId ?: return
        client.pause(thread)
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

    // ==================== Events ====================

    /** Wires the adapter events; call once before [start]. */
    fun wireEvents() {
        client.onEvent(DapEvent.Stopped, StoppedEventBody.serializer()) { body ->
            pendingStop = true
            stopReason = body?.reason ?: "unknown"
        }
        client.onEvent(DapEvent.Exited) {
            running = false
            stoppedThreadId = null
        }
        client.onEvent(DapEvent.Terminated) {
            active = false
            running = false
            stoppedThreadId = null
            editor.setExecutionLine(null)
        }
        client.onEvent(DapEvent.Output, OutputEventBody.serializer()) { body ->
            body?.output?.let { line ->
                output.add(line.trimEnd())
                if (output.size > 500) output.removeAt(0)
            }
        }
    }

    /** Last stop reason (e.g. "breakpoint", "step", "entry"). */
    var stopReason: String = ""
        private set

    /**
     * Applies pending adapter state on the render thread. Call once per
     * frame before rendering the debug UI.
     */
    fun drain() {
        if (!pendingStop) return
        pendingStop = false
        scope.launch {
            val threads = try {
                client.threads().threads
            } catch (_: Exception) {
                emptyList()
            }
            val threadId = threads.firstOrNull()?.id ?: 0
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

            val top = frames.firstOrNull()
            val pos = if (top != null && top.source?.path == sourcePath) {
                DocPos(top.line - 1, 0)
            } else null
            if (pos != null) {
                editor.setExecutionLine(pos.line)
                editor.unfoldAround(pos.line)
            }
            refreshScopesAndVariables(0)
            callbacks.onStopped?.invoke(pos)
        }
    }
}

/** DAP `stopped` event body. */
@kotlinx.serialization.Serializable
data class StoppedEventBody(
    val reason: String,
    val description: String? = null,
    val threadId: Int? = null,
    val allThreadsStopped: Boolean? = null,
)

/** DAP `output` event body. */
@kotlinx.serialization.Serializable
data class OutputEventBody(
    val category: String? = null,
    val output: String,
    val variablesReference: Int? = null,
    val source: Source? = null,
    val line: Int? = null,
    val column: Int? = null,
)
