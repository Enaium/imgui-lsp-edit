package cn.enaium.lsp.edit.example

import java.nio.file.Path

/**
 * Real-LSP example entry point.
 *
 * Usage:
 *   --frames N      exit after N frames (headless CI)
 *   --file <path>   the .kt file to open (default ~/demo.kt, sample if absent)
 *   --server <cmd>  kotlin-language-server command (default "kotlin-lsp")
 */
fun main(args: Array<String>) {
    var frames = Int.MAX_VALUE
    var file: Path? = null
    // kotlin-language-server defaults to socket mode; --stdio is required
    // for the pipe transport this example uses.
    var server = "kotlin-lsp --stdio"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--frames" -> frames = args.getOrNull(i + 1)?.toIntOrNull() ?: Int.MAX_VALUE
            "--file" -> file = args.getOrNull(i + 1)?.let { Path.of(it) }
            "--server" -> server = args.getOrNull(i + 1) ?: server
        }
        i++
    }
    println("lsp-edit kotlin-lsp example (frames=$frames, server=$server, file=${file ?: "sample"})")
    runKotlinLspExample(
        frames = frames,
        file = file ?: Path.of(System.getProperty("user.home"), "demo.kt"),
        serverCommand = server.split(" "),
    )
}
