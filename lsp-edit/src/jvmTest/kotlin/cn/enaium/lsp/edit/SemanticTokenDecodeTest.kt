package cn.enaium.lsp.edit

import cn.enaium.lsp.edit.lsp.LspClient
import cn.enaium.lsp.edit.lsp.LspEditor
import cn.enaium.lsp.model.SemanticTokens
import cn.enaium.lsp.model.SemanticTokensLegend
import kotlin.test.Test
import kotlin.test.assertEquals

/** Semantic-token delta decoding: deltaStart is relative to the previous
 *  token's START, so token positions must not drift right. */
class SemanticTokenDecodeTest {

    @Test
    fun deltasDecodeToExactPositions() {
        val ctx = cn.enaium.imgui.ImGui.createContext()
        try {
            val editor = Editor(initialText = "abc def\nghij", language = Language.kotlin)
            val pair = cn.enaium.lsp.edit.lsp.InMemoryTransportPair()
            val client = LspClient(pair.a)
            val lspEditor = LspEditor(editor, client, uri = "file:///t.kt", languageId = "kotlin")
            val legend = SemanticTokensLegend(
                tokenTypes = listOf("keyword", "string"),
                tokenModifiers = emptyList(),
            )
            // token1: (0,0,3) keyword; token2: same line, +5 -> start 5, len 2;
            // token3: next line (deltaLine 1), start 4, len 1.
            val tokens = SemanticTokens(
                data = listOf(
                    0, 0, 3, 0, 0,
                    0, 5, 2, 0, 0,
                    1, 4, 1, 0, 0,
                ),
            )
            val spans = lspEditor.decodeSemanticTokens(tokens, legend)
            assertEquals(TokenSpan(0, 3, PaletteIndex.KEYWORD), spans[0]?.get(0))
            assertEquals(TokenSpan(5, 7, PaletteIndex.KEYWORD), spans[0]?.get(1))
            assertEquals(TokenSpan(4, 5, PaletteIndex.KEYWORD), spans[1]?.get(0))
        } finally {
            cn.enaium.imgui.ImGui.destroyContext(ctx)
        }
    }
}
