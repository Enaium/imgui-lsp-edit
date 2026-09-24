package cn.enaium.lsp.edit

import cn.enaium.imgui.ImFont
import cn.enaium.imgui.ImFontAtlas
import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui

/**
 * Fonts for the editor: one main face, plus a fallback merged into it.
 *
 * ImGui resolves a glyph through the atlas entry that owns the text, so a
 * character the main face does not cover (CJK, symbols) renders as a tofu box
 * unless a face that covers it was merged into that same entry. Merging is
 * exactly what [fallbackFontPath] configures — it is not a second font the
 * caller switches to by hand.
 */
data class EditorFontSettings(
    /**
     * TTF/OTF/TTC file for editor text. Null keeps ImGui's built-in font,
     * which covers Latin only.
     */
    val mainFontPath: String? = null,
    /**
     * TTF/OTF/TTC merged into the main face for the glyphs the main font
     * lacks. Null merges nothing.
     */
    val fallbackFontPath: String? = null,
    /** Size of the main face, before [installEditorFonts]'s density scaling. */
    val sizePx: Float = 13f,
    /**
     * Size of a second, smaller face for inlay hints and inline code chips;
     * 0 installs none. It is the same main/fallback pairing at that size, so
     * small text keeps the fallback's coverage too.
     */
    val smallSizePx: Float = 0f,
)

/**
 * Faces created by [installEditorFonts]: hand [main] to `Editor.font` and
 * [small] to `Editor.inlayHintFont` / `MarkdownCode.codeFont`.
 */
data class EditorFonts(val main: ImFont, val small: ImFont?)

/**
 * Adds [settings] to the current ImGui font atlas.
 *
 * Call it while the host is still setting up, before the atlas is built
 * (`ImGui.getIO().fonts.build()`): a face cannot join the atlas afterwards,
 * and the texture the host uploads is what those faces end up in.
 *
 * [density] is the framebuffer scale (HiDPI): sizes are multiplied by it and
 * the rasterizer is told to match, so glyphs are rasterized for the real
 * pixel grid instead of being upscaled and blurred.
 *
 * The configured paths must exist. imgui-kmp's binding wraps the native font
 * pointer unconditionally — `addFontFromFileTTF` returns a non-null [ImFont]
 * even when nothing was loaded, and neither [ImFont] nor `ImFontAtlas` exposes
 * a way to tell — so a typo cannot be reported from here: it leaves the atlas
 * without that face (the editor then renders nothing). Check the paths where
 * they are configured, as the examples do before accepting a system font.
 */
fun installEditorFonts(settings: EditorFontSettings, density: Float = 1f): EditorFonts {
    val atlas = ImGui.getIO().fonts
    val mainPath = settings.mainFontPath
    val fallbackPath = settings.fallbackFontPath
    val main = atlas.addFace(mainPath, settings.sizePx * density, density)
    if (fallbackPath != null) atlas.mergeFallback(fallbackPath, settings.sizePx * density, density)
    val small = if (settings.smallSizePx > 0f) {
        // Same pairing as the main face, smaller: small text (inlay hints,
        // inline code chips) then has the fallback's coverage too.
        val sizePx = settings.smallSizePx * density
        val face = atlas.addFace(mainPath, sizePx, density)
        if (fallbackPath != null) atlas.mergeFallback(fallbackPath, sizePx, density)
        face
    } else {
        null
    }
    return EditorFonts(main, small)
}

/** Adds the main face: [path]'s file, or ImGui's built-in font when null. */
private fun ImFontAtlas.addFace(path: String?, sizePx: Float, density: Float): ImFont =
    if (path == null) {
        addFontDefault(ImFontConfig(sizePixels = sizePx, rasterizerDensity = density))
    } else {
        addFontFromFileTTF(path, ImFontConfig(sizePixels = sizePx, rasterizerDensity = density))
    }

/**
 * Merges [path] into the face added last, at that face's size.
 *
 * `mergeMode` is what makes a fallback work at all: without it ImGui starts a
 * separate atlas entry that only text explicitly pushed with that font would
 * use, and the editor's text would keep showing tofu boxes.
 */
private fun ImFontAtlas.mergeFallback(path: String, sizePx: Float, density: Float) {
    addFontFromFileTTF(
        path,
        ImFontConfig(sizePixels = sizePx, mergeMode = true, rasterizerDensity = density),
    )
}
