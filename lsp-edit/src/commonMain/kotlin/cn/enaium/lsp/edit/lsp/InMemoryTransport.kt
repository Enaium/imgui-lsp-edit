package cn.enaium.lsp.edit.lsp

import cn.enaium.lsp.jsonrpc.MessageTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/**
 * An in-memory duplex pair of [MessageTransport]s: messages written to
 * [a] are received by [b] and vice versa. Used by tests and the example's
 * in-process demo language server — no OS pipes or processes involved.
 */
class InMemoryTransportPair {
    private val aToB = Channel<String>(Channel.UNLIMITED)
    private val bToA = Channel<String>(Channel.UNLIMITED)
    private var closed = false

    val a: MessageTransport = object : MessageTransport {
        override fun send(message: String) {
            if (!closed) aToB.trySend(message)
        }

        override fun receive(): String? = runBlocking { bToA.receiveCatching().getOrNull() }
    }

    val b: MessageTransport = object : MessageTransport {
        override fun send(message: String) {
            if (!closed) bToA.trySend(message)
        }

        override fun receive(): String? = runBlocking { aToB.receiveCatching().getOrNull() }
    }

    fun close() {
        closed = true
        aToB.close()
        bToA.close()
    }
}