@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import cn.enaium.lsp.edit.example.runLspEditorExample
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Native entry point (Kotlin/Native requires the executable entry in the
 * default package). Runs the example until the window closes;
 * `IMGUI_KMP_FRAMES` limits the number of frames for headless CI runs.
 */
fun main() {
    val frames = getenv("IMGUI_KMP_FRAMES")?.toKString()?.toIntOrNull() ?: Int.MAX_VALUE
    println("lsp-edit example (frames=$frames)")
    runLspEditorExample(frames)
}
