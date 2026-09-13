package cn.enaium.lsp.edit.example

import cn.enaium.lsp.model.DidOpenTextDocumentParams
import cn.enaium.lsp.model.SemanticTokensParams
import cn.enaium.lsp.model.TextDocumentIdentifier
import cn.enaium.lsp.model.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DemoLanguageServer semantic-token emission: on one line deltaStart is the
 * gap between this token's start and the previous token's start (LSP spec).
 * Encoding against the previous token's end shifted every following token
 * right by the accumulated lengths, visibly misaligning the highlighting.
 */
class SemanticTokenEncodingTest {

    private fun tokensFor(text: String): List<Int> {
        val server = DemoLanguageServer()
        val service = server.textDocumentService()
        val uri = "file:///t.kt"
        service.didOpen(DidOpenTextDocumentParams(textDocument = TextDocumentItem(uri, "kotlin", 1, text)))
        return service.semanticTokensFull(
            SemanticTokensParams(textDocument = TextDocumentIdentifier(uri)),
        )!!.data
    }

    @Test
    fun deltaStartIsRelativeToPreviousTokenStart() {
        // "val x = 1" -> tokens at columns 0, 4, 8 (lengths 3, 1, 1).
        val data = tokensFor("val x = 1")
        assertEquals(15, data.size)
        assertEquals(0, data[1])  // "val" starts at 0
        assertEquals(4, data[6])  // "x" starts at 4: gap from 0 (end-relative encoding produced 1)
        assertEquals(4, data[11]) // "1" starts at 8: gap from 4 (end-relative encoding produced 3)
    }

    @Test
    fun deltaStartIsAbsoluteAfterLineChange() {
        // Line 1: "val x"; line 2: "1" -> deltaLine 1, deltaStart is the column (2).
        val data = tokensFor("val x\n  1")
        assertEquals(0, data[1]) // "val" at column 0 on line 0
        assertEquals(4, data[6]) // "x" at column 4 on line 0
        assertEquals(1, data[10]) // line delta 1
        assertEquals(2, data[11]) // "1" at column 2 on line 1 (absolute)
    }
}
