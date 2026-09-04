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

/**
 * Best-effort system font that covers CJK glyphs, used as the editor's
 * fallback font so Chinese/Japanese comments render instead of tofu boxes.
 */
private fun fallbackFontPath(): String? =
    listOf(
        "/System/Library/Fonts/PingFang.ttc",        // macOS
        "/System/Library/Fonts/STHeiti Light.ttc",   // macOS (older)
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", // Linux
        "C:/Windows/Fonts/msyh.ttc",                 // Windows
    ).firstOrNull { java.io.File(it).exists() }

fun main(args: Array<String>) {
    val frames = parseFrames(args)
    val fallback = fallbackFontPath()
    println("lsp-edit example (frames=$frames, fallbackFont=${fallback ?: "none"})")
    runLspEditorExample(frames, fallbackFontPath = fallback)
}
