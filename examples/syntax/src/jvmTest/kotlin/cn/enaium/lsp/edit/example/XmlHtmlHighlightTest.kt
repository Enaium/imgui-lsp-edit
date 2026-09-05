package cn.enaium.lsp.edit.example

import cn.enaium.lsp.edit.PaletteIndex
import cn.enaium.lsp.edit.TokenSpan
import cn.enaium.lsp.edit.syntax.treesitter.TreeSitterHighlighter
import kotlin.test.Test
import kotlin.test.assertTrue

/** XML/HTML tag highlighting: single tags must color like paired tags. */
class XmlHtmlHighlightTest {

    private fun spansFor(spec: TreeSitterLanguageSpec, text: String): List<List<TokenSpan>> {
        val hl = TreeSitterHighlighter(spec.language(), spec.query)
        hl.setText(text)
        return (0 until text.lineSequence().count()).map { hl.spansForLine(it) ?: emptyList() }
    }

    private fun spanAt(line: List<TokenSpan>, char: Int, palette: Int): Boolean =
        line.any { char >= it.start && char < it.end && it.palette == palette }

    @Test
    fun xmlSelfClosingTagIsHighlighted() {
        val spans = spansFor(TreeSitterLanguageSpec.XML, "<a/>")
        // 'a' at char 1 must be inside a keyword-colored span.
        assertTrue(spanAt(spans[0], 1, PaletteIndex.KEYWORD), "single tag name not keyword-colored: ${spans[0]}")
    }

    @Test
    fun xmlPairedTagIsHighlighted() {
        val spans = spansFor(TreeSitterLanguageSpec.XML, "<a></a>")
        assertTrue(spanAt(spans[0], 1, PaletteIndex.KEYWORD), "open tag name not keyword: ${spans[0]}")
        assertTrue(spanAt(spans[0], 4, PaletteIndex.KEYWORD), "close tag name not keyword: ${spans[0]}")
    }

    @Test
    fun xmlAttributesStillHighlighted() {
        val spans = spansFor(TreeSitterLanguageSpec.XML, "<a href=\"x\"/>")
        // 'href' at char 3 is a property.
        assertTrue(spanAt(spans[0], 3, PaletteIndex.KNOWN_IDENTIFIER), "attribute not property-colored: ${spans[0]}")
        assertTrue(spanAt(spans[0], 1, PaletteIndex.KEYWORD), "single tag name not keyword: ${spans[0]}")
    }

    @Test
    fun htmlSelfClosingTagIsHighlighted() {
        val spans = spansFor(TreeSitterLanguageSpec.HTML, "<br/>")
        assertTrue(spanAt(spans[0], 1, PaletteIndex.KEYWORD), "html single tag name not keyword: ${spans[0]}")
    }

    @Test
    fun xmlDeclarationIsHighlighted() {
        val spans = spansFor(TreeSitterLanguageSpec.XML, "<?xml version=\"1.0\"?>")
        // 'xml' at char 2: declaration keyword.
        assertTrue(spanAt(spans[0], 2, PaletteIndex.KEYWORD), "xml declaration not keyword-colored: ${spans[0]}")
        // 'version' at char 6: declaration attributes use the same property
        // color as element attributes.
        assertTrue(spanAt(spans[0], 6, PaletteIndex.KNOWN_IDENTIFIER), "version attr not property: ${spans[0]}")
        // '1.0' at char 15..18 must be string-colored (VersionNum capture;
        // the surrounding quotes stay keyword).
        assertTrue(spanAt(spans[0], 15, PaletteIndex.STRING), "decl value not string: ${spans[0]}")
    }

    @Test
    fun xmlDoctypeIsHighlighted() {
        val spans = spansFor(TreeSitterLanguageSpec.XML, "<!DOCTYPE html>")
        assertTrue(spanAt(spans[0], 2, PaletteIndex.KEYWORD), "doctype not keyword-colored: ${spans[0]}")
    }

    @Test
    fun xmlFullDocumentHighlightsDeclarationAndTags() {
        val spans = spansFor(
            TreeSitterLanguageSpec.XML,
            "<?xml version=\"1.0\"?>\n<root><child/></root>",
        )
        assertTrue(spanAt(spans[0], 2, PaletteIndex.KEYWORD), "declaration keyword: ${spans[0]}")
        assertTrue(spanAt(spans[0], 6, PaletteIndex.KNOWN_IDENTIFIER), "decl version property: ${spans[0]}")
        assertTrue(spanAt(spans[0], 15, PaletteIndex.STRING), "decl value string: ${spans[0]}")
        assertTrue(spanAt(spans[1], 1, PaletteIndex.KEYWORD), "root tag keyword: ${spans[1]}")
        assertTrue(spanAt(spans[1], 7, PaletteIndex.KEYWORD), "child tag keyword: ${spans[1]}")
    }
}
