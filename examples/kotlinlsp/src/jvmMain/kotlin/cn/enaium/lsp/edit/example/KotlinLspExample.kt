package cn.enaium.lsp.edit.example

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.lsp.edit.Editor
import cn.enaium.lsp.edit.Language
import cn.enaium.lsp.edit.lsp.LspClient
import cn.enaium.lsp.edit.lsp.LspEditor
import cn.enaium.lsp.jsonrpc.MessageTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.Volatile
import kotlin.concurrent.thread

/**
 * Real-LSP example: connects the editor to the actual
 * [kotlin-language-server](https://github.com/fwcd/kotlin-language-server)
 * (the `kotlin-lsp` binary) over stdio, opens a real `.kt` file and shows
 * diagnostics, hover, completion, go-to-definition and semantic tokens.
 *
 * Run: `./gradlew :examples:kotlinlsp:jvmRun --args="--file /path/to/File.kt"`
 * The server binary is located via `--server` (default `kotlin-lsp --stdio`;
 * kotlin-language-server defaults to socket mode, so `--stdio` is required
 * for a pipe transport).
 */
fun runKotlinLspExample(
    frames: Int = Int.MAX_VALUE,
    file: Path = Path.of(System.getProperty("user.home"), "demo.kt"),
    serverCommand: List<String> = listOf("kotlin-lsp", "--stdio"),
) {
    var app: KotlinLspApp? = null
    SdlRendererApp.run(
        title = "lsp-edit kotlin-lsp example",
        frames = frames,
        init = { extraFont ->
            val a = KotlinLspApp(file, serverCommand)
            a.editor.inlayHintFont = extraFont
            app = a
        },
        draw = { frame -> app?.draw(frame) },
        close = { app?.close() },
        onTextInput = { text ->
            val a = app
            if (a != null && a.editor.isFocused) {
                a.editor.inputText(text)
                true
            } else {
                false
            }
        },
        textInputWanted = { app?.editor?.isFocused == true },
    )
}

private class KotlinLspApp(
    private val file: Path,
    serverCommand: List<String>,
) {
    private val text: String = if (Files.exists(file)) Files.readString(file) else SAMPLE_KOTLIN

    val editor = Editor(
        initialText = text,
        language = Language.kotlin,
    )

    @Volatile
    private var status = "starting $serverCommand…"
    private var diagnosticsCount = 0

    private val process: Process
    private val client: LspClient
    private val lspEditor: LspEditor

    init {
        val pb = ProcessBuilder(serverCommand)
        // stderr (server logs) stays on its own pipe so stdout is
        // exclusively LSP frames for the transport.
        val p = pb.start()
        process = p
        // Drain stderr so a chatty server never blocks on a full pipe.
        thread(isDaemon = true) {
            p.errorStream.bufferedReader().forEachLine { status = "server: $it" }
        }
        val transport = ProcessMessageTransport(
            p.inputStream,
            p.outputStream,
            onClosed = { status = "server closed" },
        )
        client = LspClient(transport)
        lspEditor = LspEditor(
            editor = editor,
            client = client,
            uri = file.toUri().toString(),
            languageId = "kotlin",
        )
        GlobalScope.launch(Dispatchers.Default) {
            try {
                status = "connected to ${serverCommand.firstOrNull()}"
                println("kotlin-lsp: spawned pid=${p.pid()}, starting LSP handshake")
                lspEditor.start(
                    processId = p.pid().toInt(),
                    rootUri = file.toAbsolutePath().parent.toUri().toString(),
                    clientName = "lsp-edit-kotlin-lsp",
                )
                status = "ready"
                println(
                    "kotlin-lsp: LSP initialized, " +
                        "capabilities=${lspEditor.serverCapabilities?.let { "present" } ?: "none"}",
                )
            } catch (e: Exception) {
                status = "server failed: ${e.message}"
                println("kotlin-lsp: server failed: ${e.message}")
            }
        }
        lspEditor.onShowMessage = { status = it }
        lspEditor.onLogMessage = { status = it }
    }

    fun draw(frame: Int) {
        lspEditor.drain()

        val display = ImGui.getIO().displaySize
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(display, ImGuiCond.ALWAYS)
        ImGui.begin(
            "LSP Editor (kotlin-lsp)",
            null,
            ImGuiWindowFlags.NO_MOVE or
                ImGuiWindowFlags.NO_RESIZE or
                ImGuiWindowFlags.NO_TITLE_BAR or
                ImGuiWindowFlags.NO_COLLAPSE or
                ImGuiWindowFlags.NO_SAVED_SETTINGS,
        )

        if (ImGui.button("Go to definition (F12)")) lspEditor.gotoDefinition()
        ImGui.sameLine()
        if (ImGui.button("Completion (Ctrl+Space)")) lspEditor.requestCompletion()

        diagnosticsCount = editor.markers.size

        editor.overlay = { lspEditor.renderCompletionPopup() }
        editor.render("##editor", ImVec2(-1f, -1f))
        lspEditor.renderHoverTooltip()

        val cursor = editor.cursor
        ImGui.text(
            "status: $status   |   ${file.fileName}   |   Ln ${cursor.line + 1}, Col ${cursor.index + 1}   |   " +
                "diagnostics: $diagnosticsCount   |   frame: $frame",
        )

        if (ImGui.isKeyPressed(ImGuiKey.F12)) lspEditor.gotoDefinition()
        if (ImGui.isKeyDown(ImGuiKey.MOD_CTRL) && ImGui.isKeyPressed(ImGuiKey.SPACE)) {
            lspEditor.requestCompletion()
        }

        ImGui.end()
    }

    fun close() {
        try {
            lspEditor.close()
        } catch (_: Exception) {
        }
        try {
            process.destroy()
        } catch (_: Exception) {
        }
    }

    private companion object {
        val SAMPLE_KOTLIN = """
            |package demo
            |
            |data class Point(val x: Double, val y: Double)
            |
            |fun distance(a: Point, b: Point): Double {
            |    val dx = a.x - b.x
            |    val dy = a.y - b.y
            |    return kotlin.math.sqrt(dx * dx + dy * dy)
            |}
            |""".trimMargin()
    }
}

/**
 * Content-Length framed stdio transport for a language server process
 * (LSP over stdin/stdout).
 */
internal class ProcessMessageTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClosed: () -> Unit = {},
) : MessageTransport {
    @Volatile
    private var closed = false

    override fun send(message: String) {
        if (closed) return
        try {
            val bytes = message.toByteArray(Charsets.UTF_8)
            output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        } catch (e: java.io.IOException) {
            // The server process died (e.g. closed stdin after crash): stop
            // sending so the UI thread never sees the pipe error.
            if (!closed) {
                closed = true
                onClosed()
            }
        }
    }

    /** Marks the transport dead after the process exited. */
    fun markClosed() {
        closed = true
    }

    override fun receive(): String? {
        // Read headers until an empty line.
        val headers = StringBuilder()
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) break
            headers.append(line).append('\n')
        }
        val contentLength = Regex("Content-Length:\\s*(\\d+)")
            .find(headers.toString())?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return String(body, Charsets.UTF_8)
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }
}
