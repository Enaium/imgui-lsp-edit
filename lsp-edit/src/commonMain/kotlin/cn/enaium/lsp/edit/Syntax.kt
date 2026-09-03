package cn.enaium.lsp.edit

/**
 * A regex-free, state-machine syntax tokenizer for a single language. A tiny
 * language surface is enough to make LSP-driven editors look right while the
 * server is warming up; semantic tokens from the server override it when they
 * arrive. The tokenizer is per-line with a carry state so multi-line
 * comments/strings work without re-tokenizing the whole document.
 */
class Language(
    val name: String,
    val keywords: Set<String>,
    val lineComment: String? = null,
    val blockCommentStart: String? = null,
    val blockCommentEnd: String? = null,
    val stringDelimiters: Set<Char> = setOf('"', '\''),
    val multiLineString: Boolean = false,
    val numberChars: String = "0123456789",
) {
    companion object {
        val cpp = Language(
            name = "cpp",
            keywords = setOf(
                "alignas", "alignof", "and", "asm", "auto", "bool", "break", "case", "catch",
                "char", "class", "const", "constexpr", "continue", "default", "delete", "do",
                "double", "else", "enum", "explicit", "export", "extern", "false", "float",
                "for", "friend", "goto", "if", "inline", "int", "long", "mutable", "namespace",
                "new", "noexcept", "nullptr", "operator", "private", "protected", "public",
                "register", "return", "short", "signed", "sizeof", "static", "struct", "switch",
                "template", "this", "throw", "true", "try", "typedef", "typename", "union",
                "unsigned", "using", "virtual", "void", "volatile", "while",
            ),
            lineComment = "//",
            blockCommentStart = "/*",
            blockCommentEnd = "*/",
        )

        val python = Language(
            name = "python",
            keywords = setOf(
                "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
                "del", "elif", "else", "except", "False", "finally", "for", "from", "global",
                "if", "import", "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass",
                "raise", "return", "True", "try", "while", "with", "yield",
            ),
            lineComment = "#",
            stringDelimiters = setOf('"', '\''),
            multiLineString = true,
        )

        val json = Language(
            name = "json",
            keywords = setOf("true", "false", "null"),
            stringDelimiters = setOf('"'),
        )

        val kotlin = Language(
            name = "kotlin",
            keywords = setOf(
                "as", "break", "class", "continue", "data", "do", "else", "enum",
                "false", "for", "fun", "if", "import", "in", "interface", "internal",
                "is", "null", "object", "override", "package", "private", "public",
                "return", "sealed", "suspend", "this", "true", "val", "var", "when",
                "while",
            ),
            lineComment = "//",
            blockCommentStart = "/*",
            blockCommentEnd = "*/",
        )
    }
}

/**
 * A colored span within a line: [start] (inclusive) to [end] (exclusive) map
 * to [PaletteIndex] entries.
 */
data class TokenSpan(val start: Int, val end: Int, val palette: Int)

/** Per-line tokenization result plus the carry state for the next line. */
class TokenizedLine(
    val spans: List<TokenSpan>,
    val carryState: Int,
)

/** Tokenizer carry-state bits. */
object TokenState {
    const val NONE = 0
    const val BLOCK_COMMENT = 1 shl 0
    const val STRING = 1 shl 1
}

object SyntaxHighlighter {

    /**
     * Tokenizes [line] given the previous line's [carryState]. Returns spans
     * (in palette-index space) plus the next carry state. Multi-line
     * comments/strings are carried; line comments end at EOL.
     */
    fun tokenize(language: Language, line: String, carryState: Int): TokenizedLine {
        val spans = mutableListOf<TokenSpan>()
        var state = carryState
        var i = 0
        var tokenStart = 0
        var tokenPalette = PaletteIndex.TEXT

        fun flush(start: Int, end: Int, palette: Int) {
            if (end > start) spans += TokenSpan(start, end, palette)
        }

        // Multi-line block comment carried from a previous line.
        if (state and TokenState.BLOCK_COMMENT != 0) {
            val end = language.blockCommentEnd?.let { line.indexOf(it) } ?: -1
            if (end >= 0) {
                flush(0, end + (language.blockCommentEnd?.length ?: 0), PaletteIndex.COMMENT)
                state = state and TokenState.BLOCK_COMMENT.inv()
                i = end + (language.blockCommentEnd?.length ?: 0)
            } else {
                flush(0, line.length, PaletteIndex.COMMENT)
                return TokenizedLine(spans, state)
            }
        } else if (state and TokenState.STRING != 0) {
            val end = findStringEnd(language, line, 0)
            if (end < 0) {
                flush(0, line.length, PaletteIndex.STRING)
                return TokenizedLine(spans, state)
            }
            flush(0, end, PaletteIndex.STRING)
            i = end
            state = state and TokenState.STRING.inv()
        }

        val isIdentStart = { c: Char -> c.isLetter() || c == '_' }
        val isIdent = { c: Char -> c.isLetterOrDigit() || c == '_' }
        val isNumber = { c: Char -> c in language.numberChars || c == '.' || c == 'x' || c == 'X' }

        while (i < line.length) {
            val c = line[i]

            // Block comment start.
            if (language.blockCommentStart != null &&
                line.startsWith(language.blockCommentStart, i)
            ) {
                val end = language.blockCommentEnd?.let { line.indexOf(it, i) }
                if (end == null || end < 0) {
                    flush(tokenStart, i, tokenPalette)
                    flush(i, line.length, PaletteIndex.COMMENT)
                    state = state or TokenState.BLOCK_COMMENT
                    tokenStart = line.length
                    break
                } else {
                    flush(tokenStart, i, tokenPalette)
                    val endPos = end + (language.blockCommentEnd?.length ?: 0)
                    flush(i, endPos, PaletteIndex.COMMENT)
                    tokenStart = endPos
                    i = endPos
                    continue
                }
            }

            // Line comment.
            if (language.lineComment != null && line.startsWith(language.lineComment, i)) {
                flush(tokenStart, i, tokenPalette)
                flush(i, line.length, PaletteIndex.COMMENT)
                tokenStart = line.length
                break
            }

            // String start.
            if (c in language.stringDelimiters) {
                flush(tokenStart, i, tokenPalette)
                val end = findStringEnd(language, line, i + 1)
                if (end < 0) {
                    flush(i, line.length, PaletteIndex.STRING)
                    if (language.multiLineString) state = state or TokenState.STRING
                    tokenStart = line.length
                    break
                }
                flush(i, end, PaletteIndex.STRING)
                tokenStart = end
                i = end
                continue
            }

            // Identifier / keyword.
            if (isIdentStart(c)) {
                val start = i
                while (i < line.length && isIdent(line[i])) i++
                flush(tokenStart, start, tokenPalette)
                tokenStart = i
                tokenPalette = PaletteIndex.TEXT
                val word = line.substring(start, i)
                if (word in language.keywords) {
                    spans += TokenSpan(start, i, PaletteIndex.KEYWORD)
                } else {
                    spans += TokenSpan(start, i, PaletteIndex.IDENTIFIER)
                }
                continue
            }

            // Number.
            if (c.isDigit()) {
                val start = i
                while (i < line.length && isNumber(line[i])) i++
                flush(tokenStart, start, tokenPalette)
                tokenStart = i
                spans += TokenSpan(start, i, PaletteIndex.NUMBER)
                continue
            }

            // Punctuation.
            flush(i, i + 1, PaletteIndex.PUNCTUATION)
            tokenStart = i + 1
            i++
        }

        if (tokenStart < line.length) {
            flush(tokenStart, line.length, PaletteIndex.TEXT)
        }
        return TokenizedLine(spans, state)
    }

    private fun findStringEnd(language: Language, line: String, from: Int): Int {
        var i = from
        val delim = line[from - 1]
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i += 2
                delim -> return i + 1
                '\n' -> return -1
                else -> i++
            }
        }
        return -1
    }
}