package cn.enaium.lsp.edit.example

/**
 * Parses `--frames N` (exit after N frames, for headless CI runs) and
 * `--font <path>` (main editor font; the built-in font when absent).
 */
private fun parseArgs(args: Array<String>): Pair<Int, String?> {
    var frames = Int.MAX_VALUE
    var mainFont: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--frames" -> {
                frames = args.getOrNull(i + 1)?.toIntOrNull() ?: Int.MAX_VALUE
                i++
            }
            "--font" -> {
                mainFont = args.getOrNull(i + 1)
                i++
            }
        }
        i++
    }
    return frames to mainFont
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
    val (frames, requestedFont) = parseArgs(args)
    // The font atlas cannot report a file that failed to load, so reject a bad
    // path here instead of ending up with an atlas that has no face.
    val mainFont = requestedFont?.takeIf { java.io.File(it).exists() }
    if (requestedFont != null && mainFont == null) {
        println("main font not found: $requestedFont")
    }
    val fallback = fallbackFontPath()
    println(
        "lsp-edit tree-sitter syntax example (frames=$frames, mainFont=${mainFont ?: "built-in"}, " +
            "fallbackFont=${fallback ?: "none"})",
    )
    runSyntaxExample(frames, mainFontPath = mainFont, fallbackFontPath = fallback)
}
