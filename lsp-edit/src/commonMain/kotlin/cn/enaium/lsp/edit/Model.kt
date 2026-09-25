package cn.enaium.lsp.edit

/**
 * A zero-based position inside the document: [line] and [index] (character
 * offset within the line). Offsets are UTF-16 code units — the LSP default
 * `positionEncoding`, and exactly Kotlin `String` indexing — so positions map
 * 1:1 between the editor and [cn.enaium.lsp.model.Position].
 */
data class DocPos(val line: Int, val index: Int) : Comparable<DocPos> {

    override fun compareTo(other: DocPos): Int = compareBy<DocPos> { it.line }.thenBy { it.index }
        .compare(this, other)

    operator fun plus(delta: Int): DocPos = DocPos(line, index + delta)
    operator fun minus(delta: Int): DocPos = DocPos(line, index - delta)

    fun coerceAtLeast(min: DocPos): DocPos = if (this < min) min else this
    fun coerceAtMost(max: DocPos): DocPos = if (this > max) max else this
}

/** An inclusive character span on a single line. */
data class DocRange(val start: DocPos, val end: DocPos) {
    val empty: Boolean get() = start == end
}

/** One text mutation: an insert at [pos] or a deletion of [text] at [pos]. */
data class EditOp(val pos: DocPos, val text: String, val insert: Boolean)

/**
 * A range replacement in the document's current coordinates,
 * [from]..[to) -> [text]. Batched via [Editor.applyEdits] so the whole
 * batch is one undo step.
 */
data class EditorEdit(val from: DocPos, val to: DocPos, val text: String)

/**
 * Packed RGBA color (0xRRGGBBAA). Palette entries are stored as [Long]s so
 * full-range hex constants (`0xFF11223344L`) round-trip on every target.
 */
typealias Color = Long

/** Logical palette slots. Text rendering picks the palette entry by index. */
object PaletteIndex {
    const val TEXT = 0
    const val KEYWORD = 1
    const val DECLARATION = 2
    const val NUMBER = 3
    const val STRING = 4
    const val PUNCTUATION = 5
    const val PREPROCESSOR = 6
    const val IDENTIFIER = 7
    const val KNOWN_IDENTIFIER = 8
    const val COMMENT = 9
    const val BACKGROUND = 10
    const val CURSOR = 11
    const val SELECTION = 12
    const val WHITESPACE = 13
    const val MATCHING_BRACKET_BG = 14
    const val MATCHING_BRACKET_ACTIVE = 15
    const val LINE_NUMBER = 16
    const val CURRENT_LINE_BG = 17
    const val CURRENT_LINE_FILL = 18
    const val LINE_NUMBER_SELECTED = 19
    const val MARKER = 20
    const val SEARCH_RESULT_BG = 21
    const val INLAY_HINT = 22
    const val BREAKPOINT = 23
    const val EXECUTION_LINE = 24
    const val COUNT = 25
}

/** Built-in dark palette (packed 0xRRGGBBAA). */
object EditorPalette {
    val dark: Array<Color> = arrayOf(
        0xFFD0D0D0L, // TEXT
        0xFF569CD6L, // KEYWORD
        0xFF569CD6L, // DECLARATION
        0xFFB5CEA8L, // NUMBER
        0xFFCE9178L, // STRING
        0xFFD4D4D4L, // PUNCTUATION
        0xFFC586C0L, // PREPROCESSOR
        0xFF9CDCFEL, // IDENTIFIER
        0xFF4EC9B0L, // KNOWN_IDENTIFIER
        0xFF6A9955L, // COMMENT
        0xFF1E1E1EL, // BACKGROUND
        0xFFD4D4D4L, // CURSOR
        0xFF264F78L, // SELECTION
        0xFFFFFFFFL, // WHITESPACE
        0xFFD4D4D4L, // MATCHING_BRACKET_BG
        0xFFFFFFFFL, // MATCHING_BRACKET_ACTIVE
        0xFF808080L, // LINE_NUMBER
        0xFF2D2D30L, // CURRENT_LINE_BG
        0xFF1F1F1FL, // CURRENT_LINE_FILL
        0xFFC8C8C8L, // LINE_NUMBER_SELECTED
        0xFFFF0000L, // MARKER
        0xFF515151L, // SEARCH_RESULT_BG
        0xFF8A8A8AL, // INLAY_HINT
        0xFFE51400L, // BREAKPOINT
        0xFF265E42L, // EXECUTION_LINE
    )
}

/**
 * An inlay hint to render at a character gap on [position.line]: [label] is
 * drawn in a dimmer style after the character at [position.index] (like
 * parameter names / inferred types in VS Code).
 */
data class EditorInlayHint(val position: DocPos, val label: String)

/**
 * A collapsible line range: when collapsed, [startLine] stays visible and
 * [startLine + 1 .. endLine] are hidden. Ranges may nest.
 */
data class EditorFoldRange(val startLine: Int, val endLine: Int)

/**
 * A code lens rendered above [line]: [title] is drawn as a dim clickable
 * label (VS Code style). When [command] is non-null, clicking the lens
 * invokes it; hosts may also show a context menu.
 */
data class EditorCodeLens(
    val line: Int,
    val title: String,
    val command: String? = null,
)

/**
 * A breakpoint marker in the editor's gutter.
 *
 * The states are the ones a debugger distinguishes, and each gets its own
 * IntelliJ icon (see `BreakpointIcons`): a plain marker before any adapter has
 * judged it, verified or rejected once one has, greyed when the user disabled
 * it, and a badge when it carries a condition.
 */
data class EditorBreakpoint(
    /** 1-based line, matching [Editor.setBreakpoints]. */
    val line: Int,
    /**
     * The debug adapter's verdict: null before one has judged this breakpoint,
     * true when it verified it, false when it rejected it (e.g. no executable
     * code on that line).
     */
    val verified: Boolean? = null,
    /** False when the user turned it off: still shown, not sent to an adapter. */
    val enabled: Boolean = true,
    /** True when the breakpoint carries a condition (drawn with a "?" badge). */
    val conditional: Boolean = false,
    /** True for a logpoint, which logs instead of suspending. */
    val logpoint: Boolean = false,
)

/** Converts a packed [Color] into the 0xAABBGGRR int imgui draw calls expect. */
fun Color.toImGuiColor(): Int {
    val a = (this ushr 24) and 0xFF
    val r = (this ushr 16) and 0xFF
    val g = (this ushr 8) and 0xFF
    val b = this and 0xFF
    return ((a shl 24) or (b shl 16) or (g shl 8) or r).toInt()
}