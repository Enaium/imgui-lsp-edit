package cn.enaium.lsp.edit.lsp

import cn.enaium.lsp.jsonrpc.MessageTransport
import cn.enaium.lsp.jsonrpc.StreamMessageTransport
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Spawns a language-server process (JVM only) and exposes its stdio as a
 * [MessageTransport] with Content-Length framing. Destroy the process via
 * [close] after shutting the client down.
 */
class ServerProcessTransport(
    command: List<String>,
    workingDirectory: File? = null,
    env: Map<String, String> = emptyMap(),
) : MessageTransport, AutoCloseable {

    private val process: Process
    private val delegate: StreamMessageTransport

    init {
        val builder = ProcessBuilder(command)
        workingDirectory?.let { builder.directory(it) }
        builder.environment().putAll(env)
        process = builder.start()
        // The server reads our stdout as its stdin and vice versa.
        delegate = StreamMessageTransport(
            process.inputStream as InputStream,
            process.outputStream as OutputStream,
        )
    }

    override fun send(message: String) = delegate.send(message)

    override fun receive(): String? = delegate.receive()

    val isAlive: Boolean get() = process.isAlive

    fun destroy() {
        process.destroy()
    }

    override fun close() {
        destroy()
    }
}