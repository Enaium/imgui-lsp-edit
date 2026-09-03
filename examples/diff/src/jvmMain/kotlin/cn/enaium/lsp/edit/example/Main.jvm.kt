package cn.enaium.lsp.edit.example

/** Parses `--frames N` (exit after N frames, for headless CI runs). */
private fun parseFrames(args: Array<String>): Int {
    var frames = Int.MAX_VALUE
    var i = 0
    while (i < args.size) {
        if (args[i] == "--frames" && i + 1 < args.size) {
            frames = args[i + 1].toIntOrNull() ?: Int.MAX_VALUE
            i++
        }
        i++
    }
    return frames
}

fun main(args: Array<String>) {
    val frames = parseFrames(args)
    println("lsp-edit diff example (frames=$frames)")
    runDiffExample(frames)
}
