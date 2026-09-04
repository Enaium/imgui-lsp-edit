package cn.enaium.lsp.edit.syntax.treesitter

import cn.enaium.lsp.edit.PaletteIndex
import cn.enaium.lsp.edit.TokenSpan
import io.github.treesitter.ktreesitter.Language
import io.github.treesitter.ktreesitter.Parser
import io.github.treesitter.ktreesitter.Query
import io.github.treesitter.ktreesitter.QueryError
import io.github.treesitter.ktreesitter.Tree

/**
 * Tree-sitter driven syntax highlighter. Parses source with a grammar from
 * tree-sitter-languages-kmp, runs the language's highlight query, and maps
 * the captured nodes to per-line [TokenSpan]s that plug into the editor's
 * [cn.enaium.lsp.edit.Editor.tokenProvider].
 *
 * Byte offsets from the tree are translated to UTF-16 indices (the editor's
 * string indexing) via a byte→char offset table, so multi-byte text stays
 * aligned.
 */
class TreeSitterHighlighter(
    private val language: Language,
    querySource: String,
) {
    private val parser = Parser(language)
    private val query: Query? = try {
        Query(language, querySource)
    } catch (e: QueryError) {
        println("tree-sitter query error for ${language.name}: ${e.message}")
        null
    }

    /** Spans per document line, rebuilt on every parse. */
    private var lineSpans: Array<List<TokenSpan>> = emptyArray()

    /** Line-start UTF-16 indices for the current document. */
    private var lineStarts: IntArray = IntArray(0)

    /** Line lengths (UTF-16) for the current document. */
    private var lineLengths: IntArray = IntArray(0)

    /**
     * Re-parses [text] and rebuilds the per-line span table.
     */
    fun setText(text: String) {
        lineStarts = buildLineStarts(text)
        lineLengths = buildLineLengths(text)
        val tree = try {
            parser.parse(text) ?: return
        } catch (e: IllegalStateException) {
            println("tree-sitter parse error: ${e.message}")
            return
        }
        val byteToChar = buildByteToCharMap(text)
        val spans = Array(lineStarts.size) { mutableListOf<TokenSpan>() }
        val q = query
        if (q != null) {
            try {
                for ((_, match) in q(tree.rootNode).captures()) {
                    for (capture in match.captures) {
                        val node = capture.node
                        val palette = paletteForCapture(capture.name) ?: continue
                        val startRow = node.startPoint.row.toInt()
                        val endRow = node.endPoint.row.toInt()
                        val startChar = byteToChar[node.startByte.toInt()]
                        val endChar = byteToChar[node.endByte.toInt()]
                        if (startRow == endRow) {
                            if (startRow in spans.indices) {
                                spans[startRow] += TokenSpan(
                                    startChar - lineStarts[startRow],
                                    endChar - lineStarts[startRow],
                                    palette,
                                )
                            }
                        } else {
                            // Multi-line node: split per row.
                            if (startRow in spans.indices) {
                                val firstEnd = lineStarts.getOrElse(startRow + 1) {
                                    lineStarts[startRow] + lineLengths[startRow]
                                } - 1
                                spans[startRow] += TokenSpan(
                                    startChar - lineStarts[startRow],
                                    (firstEnd - lineStarts[startRow]).coerceAtLeast(0),
                                    palette,
                                )
                            }
                            for (row in (startRow + 1) until endRow) {
                                if (row in spans.indices) {
                                    spans[row] += TokenSpan(0, lineLengths[row], palette)
                                }
                            }
                            if (endRow in spans.indices) {
                                spans[endRow] += TokenSpan(0, endChar - lineStarts[endRow], palette)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("tree-sitter query run error: ${e.message}")
            }
        }
        // Zed semantics: when captures overlap, the later capture in the
        // query wins. tree-sitter returns captures in tree order, so reverse
        // each line's list — the editor paints the FIRST span covering a
        // position, which is now the most specific/last capture.
        lineSpans = Array(spans.size) { spans[it].asReversed() }
    }

    /** Per-line spans for [line], or null when the line has no tokens. */
    fun spansForLine(line: Int): List<TokenSpan>? =
        if (line in lineSpans.indices && lineSpans[line].isNotEmpty()) lineSpans[line] else null

    /**
     * Maps a tree-sitter capture name (zed/nvim convention) to a palette
     * slot. Captures are grouped: `.builtin`/`.special` keep the base color;
     * `.call`/`.method` keep the function color; `.class`/`.interface` keep
     * the type color. Unmapped captures (e.g. @punctuation) are skipped.
     */
    private fun paletteForCapture(name: String): Int? {
        val base = name.substringBefore('.')
        val qualifier = name.substringAfter('.', "")
        return when (base) {
            "comment" -> PaletteIndex.COMMENT
            "string" -> PaletteIndex.STRING
            "number" -> PaletteIndex.NUMBER
            "boolean" -> PaletteIndex.NUMBER
            "keyword" -> PaletteIndex.KEYWORD
            "function" -> PaletteIndex.DECLARATION
            "constructor" -> PaletteIndex.DECLARATION
            "type" -> PaletteIndex.DECLARATION
            "variable" -> when (qualifier) {
                "parameter" -> PaletteIndex.KNOWN_IDENTIFIER
                "builtin" -> PaletteIndex.KEYWORD
                else -> null
            }
            "property" -> PaletteIndex.KNOWN_IDENTIFIER
            "constant" -> PaletteIndex.NUMBER
            "label" -> PaletteIndex.KNOWN_IDENTIFIER
            "operator" -> PaletteIndex.PUNCTUATION
            // Markup (markdown/HTML-ish): headings and emphasis use the
            // declaration color, plain text stays default.
            "title" -> PaletteIndex.DECLARATION
            "text" -> null
            else -> null
        }
    }

    private fun buildLineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        for (i in text.indices) {
            if (text[i] == '\n') starts += i + 1
        }
        return starts.toIntArray()
    }

    private fun buildLineLengths(text: String): IntArray {
        val lens = ArrayList<Int>()
        var lineStart = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                lens += i - lineStart
                lineStart = i + 1
            }
        }
        lens += text.length - lineStart
        return lens.toIntArray()
    }

    /**
     * Maps UTF-8 byte offsets to UTF-16 char indices. [result][i] is the
     * UTF-16 index of the char whose first byte is at byte offset [i].
     */
    private fun buildByteToCharMap(text: String): IntArray {
        val bytes = text.encodeToByteArray()
        val map = IntArray(bytes.size + 1)
        var bytePos = 0
        var charPos = 0
        var i = 0
        while (i < text.length) {
            map[bytePos] = charPos
            val c = text[i]
            val byteLen = when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() -> {
                    // Supplementary plane: two UTF-16 units, four UTF-8 bytes.
                    // (Lone surrogates encode as a 3-byte replacement, but
                    // well-formed documents always pair them.)
                    i += 1
                    charPos += 2
                    bytePos += 4
                    map[bytePos - 4] = charPos - 2
                    i += 1
                    continue
                }
                else -> 3
            }
            bytePos += byteLen
            charPos += 1
            i += 1
        }
        map[bytes.size] = charPos
        return map
    }
}
