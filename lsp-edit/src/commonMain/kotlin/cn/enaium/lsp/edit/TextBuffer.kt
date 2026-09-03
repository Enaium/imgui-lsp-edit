package cn.enaium.lsp.edit

/**
 * A line-based text document. Lines are stored as immutable strings; edits
 * are expressed as [EditOp]s so the caller can drive undo and LSP
 * `didChange` notifications from the same mutation.
 *
 * Positions are UTF-16 code-unit offsets, matching LSP's default encoding.
 * Trailing newlines do not create phantom lines: a document ending in `\n`
 * has a final empty line (standard editor semantics).
 */
class TextBuffer(initial: String = "") {

    private val lines = mutableListOf<String>()

    init {
        setText(initial)
    }

    /** The current text, joined with `\n`. */
    fun getText(): String = lines.joinToString("\n")

    fun lineCount(): Int = lines.size

    fun line(i: Int): String = lines[i]

    fun lineLength(i: Int): Int = lines[i].length

    fun isEmpty(): Boolean = lines.size == 1 && lines[0].isEmpty()

    /** True when [pos] is inside the document (clamped semantics allowed). */
    fun isInside(pos: DocPos): Boolean = pos.line in 0 until lines.size &&
        pos.index in 0..lines[pos.line].length

    /** Clamps [pos] into valid document coordinates. */
    fun clamp(pos: DocPos): DocPos {
        if (pos.line < 0) return DocPos(0, 0)
        if (pos.line >= lines.size) return DocPos(lines.size - 1, lines.last().length)
        return DocPos(pos.line, pos.index.coerceIn(0, lines[pos.line].length))
    }

    /** Total number of characters (UTF-16 units) in the document. */
    fun charCount(): Int = lines.sumOf { it.length } + (lines.size - 1)

    /**
     * Replaces the whole document. Produces one delete + one insert so undo
     * restores the previous text in a single step.
     */
    fun setText(text: String): List<EditOp> {
        val old = getText()
        val ops = mutableListOf<EditOp>()
        if (old.isNotEmpty()) ops += EditOp(DocPos(0, 0), old, insert = false)
        if (text.isNotEmpty()) ops += EditOp(DocPos(0, 0), text, insert = true)
        lines.clear()
        if (text.isEmpty()) {
            lines += ""
        } else {
            lines += text.split("\n")
        }
        return ops
    }

    /**
     * Inserts [text] at [pos] (single line or multi-line), returning the ops.
     * [pos] is clamped; the final position is the end of the inserted text.
     */
    fun insert(pos: DocPos, text: String): List<EditOp> {
        if (text.isEmpty()) return emptyList()
        val p = clamp(pos)
        val current = lines[p.line]
        lines[p.line] = current.substring(0, p.index) + text + current.substring(p.index)
        // Split the touched line on embedded newlines.
        if (text.contains('\n')) {
            val parts = lines[p.line].split("\n")
            lines.removeAt(p.line)
            lines.addAll(p.line, parts)
        }
        return listOf(EditOp(p, text, insert = true))
    }

    /**
     * Deletes the range [start]..[end] (exclusive), returning the ops.
     * Both endpoints are clamped; an inverted range is treated as empty.
     */
    fun erase(start: DocPos, end: DocPos): List<EditOp> {
        val s = clamp(start)
        val e = clamp(end)
        if (s >= e) return emptyList()
        val deleted = getSectionText(s, e)
        if (s.line == e.line) {
            val line = lines[s.line]
            lines[s.line] = line.substring(0, s.index) + line.substring(e.index)
        } else {
            val first = lines[s.line].substring(0, s.index)
            val last = lines[e.line].substring(e.index)
            val replacement = first + last
            lines.subList(s.line, e.line + 1).clear()
            lines.add(s.line, replacement)
        }
        return listOf(EditOp(s, deleted, insert = false))
    }

    /** Returns the text covered by [start]..[end] (exclusive), endpoints clamped. */
    fun getSectionText(start: DocPos, end: DocPos): String {
        val s = clamp(start)
        val e = clamp(end)
        if (s >= e) return ""
        if (s.line == e.line) return lines[s.line].substring(s.index, e.index)
        val sb = StringBuilder()
        sb.append(lines[s.line].substring(s.index))
        for (i in s.line + 1 until e.line) sb.append('\n').append(lines[i])
        sb.append('\n').append(lines[e.line].substring(0, e.index))
        return sb.toString()
    }

    /**
     * Computes the position [delta] UTF-16 units after [pos], crossing line
     * boundaries. Negative [delta] moves backwards.
     */
    fun offset(pos: DocPos, delta: Int): DocPos {
        var line = pos.line
        var index = pos.index
        var remaining = delta
        if (remaining >= 0) {
            while (remaining > 0 && line < lines.size) {
                val len = lines[line].length
                val step = minOf(remaining, len - index)
                index += step
                remaining -= step
                if (remaining > 0) {
                    line++
                    index = 0
                    remaining--
                }
            }
            if (line >= lines.size) return DocPos(lines.size - 1, lines.last().length)
        } else {
            while (remaining < 0 && line >= 0) {
                val step = minOf(-remaining, index)
                index -= step
                remaining += step
                if (remaining < 0) {
                    line--
                    remaining++
                    index = if (line >= 0) lines[line].length else 0
                }
            }
            if (line < 0) return DocPos(0, 0)
        }
        return DocPos(line, index)
    }

    /**
     * Applies [ops] (as produced by this buffer) to the LSP model: converts
     * each op into an incremental content change. Returns change events in
     * document order (deletions before the matching insertion for
     * replacements), plus the ops themselves for undo bookkeeping.
     */
    fun toLspChanges(ops: List<EditOp>): List<EditOp> = ops

    /** Returns a deep copy of the underlying lines (for tests/tools). */
    fun snapshot(): List<String> = lines.toList()
}
