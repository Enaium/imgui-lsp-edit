package cn.enaium.lsp.edit

import cn.enaium.imgui.ImFontAtlas
import cn.enaium.imgui.ImGui
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Installing the editor's faces into the ImGui atlas.
 *
 * The contracts worth pinning are the ones a host depends on: the atlas always
 * ends up with a face (an empty one renders nothing at all), and a small face
 * appears only when it was asked for — inlay hints silently lose their font
 * otherwise.
 *
 * Note that imgui-kmp cannot report a font file that failed to load (the
 * binding wraps the native pointer unconditionally and neither [ImFont] nor
 * [ImFontAtlas] exposes a probe), so paths are validated where they are
 * configured, not here.
 */
class EditorFontsTest {

    private fun withAtlas(body: (ImFontAtlas) -> Unit) {
        val ctx = ImGui.createContext()
        try {
            body(ImGui.getIO().fonts)
        } finally {
            ImGui.destroyContext(ctx)
        }
    }

    @Test
    fun installsTheBuiltInFaceWhenNoPathIsConfigured() = withAtlas { atlas ->
        val fonts = installEditorFonts(EditorFontSettings())
        assertNotNull(fonts.main)
        assertNull(fonts.small, "no small face unless requested")
        assertTrue(atlas.build(), "an atlas with a face must build")
    }

    @Test
    fun installsTheSmallFaceWhenRequested() = withAtlas { atlas ->
        val fonts = installEditorFonts(EditorFontSettings(smallSizePx = 10f))
        assertNotNull(fonts.main)
        assertNotNull(fonts.small, "smallSizePx > 0 must add a second face")
        assertTrue(atlas.build())
    }

    @Test
    fun smallFaceIsTheSamePairingAsTheMainOne() = withAtlas { atlas ->
        // Both faces carry the fallback, so small text (inlay hints, inline
        // code) keeps the CJK coverage the body text has.
        val fonts = installEditorFonts(
            EditorFontSettings(smallSizePx = 10f, fallbackFontPath = "/nonexistent.ttf"),
        )
        assertNotNull(fonts.main)
        assertNotNull(fonts.small)
        assertTrue(atlas.build())
    }
}
