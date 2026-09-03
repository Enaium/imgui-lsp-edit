package cn.enaium.lsp.edit.lsp

import cn.enaium.lsp.jsonrpc.JsonRpcLauncher
import cn.enaium.lsp.model.*
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** Raw-launcher round trip over the in-memory transport pair. */
class RawLauncherTest {

    @Test
    fun rawRequestRoundTrip() = runBlocking {
        val inner = InMemoryTransportPair()

        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = JsonRpcLauncher(inner.b)
        server.onRequest("initialize", InitializeParams.serializer(), InitializeResult.serializer()) {
            InitializeResult(
                capabilities = ServerCapabilities(hoverProvider = HoverProvider.Enabled(true)),
                serverInfo = ServerInfo(name = "raw", version = "1.0.0"),
            )
        }
        serverScope.launch { server.listen() }

        val client = JsonRpcLauncher(inner.a)
        val listen = GlobalScope.launch { client.listen() }

        val result = client.request(
            "initialize",
            InitializeParams(capabilities = ClientCapabilities()),
            InitializeParams.serializer(),
            InitializeResult.serializer(),
        )
        assertEquals("raw", result.serverInfo?.name)

        listen.cancel()
        serverScope.cancel()
        inner.close()
    }
}