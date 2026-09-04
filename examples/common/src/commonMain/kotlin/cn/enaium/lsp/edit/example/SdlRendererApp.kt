package cn.enaium.lsp.edit.example

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLWindowFlags
import kotlin.math.max

/**
 * Bootstraps an SDL3 window + 2D renderer + the imgui renderer backend and
 * runs the frame loop, delegating the actual UI to a [draw] callback.
 *
 * Mirrors imgui-kmp's `SdlRendererApp` example bootstrap. The window runs
 * headless (SDL dummy video driver) when no display is available, which is
 * what the CI runs use.
 */
object SdlRendererApp {

    /**
     * Runs the app until the window is closed or [frames] frames were
     * rendered. [init] runs once after the imgui context + font atlas are
     * ready; [draw] runs every frame; [close] releases per-app resources
     * before the imgui context is destroyed.
     */
    fun run(
        title: String,
        frames: Int,
        init: (extraFont: cn.enaium.imgui.ImFont?) -> Unit,
        draw: (frame: Int) -> Unit,
        close: () -> Unit,
        // Extra font added to the atlas before it is built; handed to init
        // so hosts can e.g. size inlay hints smaller than the main font.
        // 0 disables the extra font.
        extraFontSizePx: Float = 0f,
        // TTF/OTF/TTC file merged into the main font with mergeMode=true:
        // glyphs the main font lacks (CJK, symbols, ...) render from this
        // fallback automatically. Null disables the fallback.
        fallbackFontPath: String? = null,
        // SDL_TextInput goes both to imgui's IO queue and to this callback.
        // Return true to consume the event (the widget already handled it).
        onTextInput: (String) -> Boolean = { false },
        // Whether the app currently wants SDL text input (editor focused).
        textInputWanted: () -> Boolean = { false },
    ) {
        SDL.setMainReady()
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
            if (SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
                println("video init fell back to the dummy driver — running headless")
            } else {
                error("SDL_Init failed: ${SDL.error()}")
            }
        }
        println("SDL ${SDL.version()} (${SDL.revision()})")
        println("Video driver: ${SDL.getCurrentVideoDriver()}")

        SDL.createWindow(
            title = title,
            width = 1280,
            height = 800,
            flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
        ).use { window ->
            SDL.createRenderer(window).use { renderer ->
                val context = ImGui.createContext()
                try {
                    val imgui = ImGuiSdlBackend(window)
                    val backend = ImGuiSdlRendererBackend(renderer)
                    imgui.init()

                    val fonts = ImGui.getIO().fonts
                    val density = maxOf(imgui.framebufferScale.x, imgui.framebufferScale.y, 1f)
                    fonts.addFontDefault(
                        ImFontConfig(
                            sizePixels = 13f * density,
                            rasterizerDensity = density,
                        ),
                    )
                    if (fallbackFontPath != null) {
                        val fallback = fonts.addFontFromFileTTF(
                            fallbackFontPath,
                            ImFontConfig(
                                sizePixels = 13f * density,
                                mergeMode = true,
                                rasterizerDensity = density,
                            ),
                        )
                        if (fallback != null) {
                            println("merged fallback font: $fallbackFontPath")
                        } else {
                            println("fallback font not loaded: $fallbackFontPath")
                        }
                    }
                    val extraFont =
                        if (extraFontSizePx > 0f) {
                            fonts.addFontDefault(
                                ImFontConfig(
                                    sizePixels = extraFontSizePx * density,
                                    rasterizerDensity = density,
                                ),
                            )
                        } else {
                            null
                        }
                    check(fonts.build()) { "font atlas build failed" }
                    val texData = fonts.getTexDataAsRGBA32()
                    val fontTextureId = backend.uploadFontTexture(texData.pixels, texData.width, texData.height)
                    fonts.setTexID(fontTextureId)

                    init(extraFont)

                    var running = true
                    var frameCount = 0
                    // Hide the OS cursor while typing, restore it when the
                    // mouse moves again (IDE behavior).
                    var cursorVisible = true
                    while (running && frameCount < frames) {
                        while (true) {
                            val event = SDL.pollEvent() ?: break
                            when (event) {
                                is cn.enaium.sdl.SDLEvent.Quit -> running = false
                                is cn.enaium.sdl.SDLEvent.Window ->
                                    if (event.type == cn.enaium.sdl.SDLWindowEventType.CLOSE_REQUESTED) running = false
                                is cn.enaium.sdl.SDLEvent.Key -> {
                                    if (event.down && !event.repeat && cursorVisible) {
                                        SDL.hideCursor()
                                        cursorVisible = false
                                    }
                                    imgui.processEvent(event)
                                }
                                is cn.enaium.sdl.SDLEvent.MouseMotion, is cn.enaium.sdl.SDLEvent.MouseButton -> {
                                    if (!cursorVisible) {
                                        SDL.showCursor()
                                        cursorVisible = true
                                    }
                                    imgui.processEvent(event)
                                }
                                is cn.enaium.sdl.SDLEvent.TextInput -> {
                                    // The editor reads characters from the host
                                    // (the Kotlin IO binding has no queue reader).
                                    if (!onTextInput(event.text)) imgui.processEvent(event)
                                }
                                is cn.enaium.sdl.SDLEvent.MouseWheel -> {
                                    // imgui convention: positive MouseWheelH scrolls
                                    // content LEFT. Natural trackpad gestures produce
                                    // positive SDL x for a leftward swipe, so the
                                    // content must scroll RIGHT — invert the x axis.
                                    // The backend's processEvent would forward the
                                    // raw x; forward the inverted pair ourselves.
                                    ImGui.getIO().addMouseWheelEvent(-event.x, event.y)
                                }
                                else -> imgui.processEvent(event)
                            }
                        }

                        // Keep SDL text input active while the editor is focused
                        // (imgui only starts it when io.wantTextInput, which a
                        // custom widget never sets).
                        val wantText = textInputWanted()
                        val textActive = SDL.textInputActive(window.id)
                        if (wantText && !textActive) SDL.startTextInput(window.id)
                        if (!wantText && textActive) SDL.stopTextInput(window.id)

                        imgui.newFrame()
                        draw(frameCount)
                        ImGui.render()

                        renderer.drawColor = SDLColor(18, 18, 24, 255)
                        renderer.clear()
                        backend.renderDrawData(ImGui.getDrawData())
                        renderer.present()
                        frameCount++
                    }

                    close()
                    backend.close()
                } finally {
                    ImGui.destroyContext(context)
                }
            }
        }
        SDL.quit()
    }
}