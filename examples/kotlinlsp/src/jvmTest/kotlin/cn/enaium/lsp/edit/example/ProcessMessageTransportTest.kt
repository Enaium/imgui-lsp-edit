package cn.enaium.lsp.edit.example

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression: typing in the editor after the language server process died
 * used to crash the UI thread with `IOException: Stream closed` from
 * ProcessMessageTransport.send. The transport must swallow writes to a dead
 * process's pipe and report the closed state instead.
 */
class ProcessMessageTransportTest {

    @Test
    fun sendAfterProcessDeathDoesNotThrow() {
        // `true` exits immediately, closing stdin/stdout pipes.
        val p = ProcessBuilder("true").start()
        p.waitFor()
        var closed = false
        val transport = ProcessMessageTransport(
            p.inputStream,
            p.outputStream,
            onClosed = { closed = true },
        )

        // Emulate the crash path: write to the pipe after the process exited.
        transport.send("""{"jsonrpc":"2.0","method":"exit"}""")

        // No exception escaped. Either the pipe was already closed and the
        // transport marked itself dead, or the write still succeeded.
        assertTrue(closed || p.isAlive)
        // receive() must not throw on a dead stream.
        assertNull(transport.receive())
    }
}
