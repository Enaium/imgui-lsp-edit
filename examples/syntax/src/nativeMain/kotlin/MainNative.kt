@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import cn.enaium.lsp.edit.example.runSyntaxExample
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Native entry point (Kotlin/Native requires the executable entry in the
 * default package). Runs the tree-sitter syntax showcase until the window
 * closes; `IMGUI_KMP_FRAMES` limits the number of frames for headless CI.
 */
fun main() {
    val frames = getenv("IMGUI_KMP_FRAMES")?.toKString()?.toIntOrNull() ?: Int.MAX_VALUE
    println("lsp-edit tree-sitter syntax example (frames=$frames)")
    runSyntaxExample(frames)
}
