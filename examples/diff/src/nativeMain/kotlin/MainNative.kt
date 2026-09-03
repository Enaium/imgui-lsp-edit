@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import cn.enaium.lsp.edit.example.runDiffExample
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** Native entry point; `IMGUI_KMP_FRAMES` limits frames for headless CI. */
fun main() {
    val frames = getenv("IMGUI_KMP_FRAMES")?.toKString()?.toIntOrNull() ?: Int.MAX_VALUE
    println("lsp-edit diff example (frames=$frames)")
    runDiffExample(frames)
}
