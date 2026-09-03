package cn.enaium.lsp.edit.diff

/**
 * A single line of a computed diff.
 *
 * [kind] classifies the line; [oldLine] / [newLine] are the 1-based line
 * numbers in the old / new document (null when the line has no counterpart,
 * i.e. added lines have no old line and removed lines have no new line);
 * [text] is the line content without any marker prefix.
 */
data class DiffLine(
    val kind: DiffKind,
    val oldLine: Int?,
    val newLine: Int?,
    val text: String,
)

enum class DiffKind { CONTEXT, ADDED, REMOVED }

/**
 * Line-oriented Myers diff over two texts.
 *
 * Produces an aligned list of [DiffLine]s: context lines carry both line
 * numbers, added lines only a new-line number, removed lines only an
 * old-line number. Equal leading/trailing lines are preserved so the
 * result is directly renderable as a side-by-side view.
 */
object Diff {

    /** Splits [text] into lines (a trailing newline yields no empty tail). */
    private fun lines(text: String): List<String> =
        text.split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }

    /** Computes the diff between [oldText] and [newText]. */
    fun compute(oldText: String, newText: String): List<DiffLine> {
        val a = lines(oldText)
        val b = lines(newText)
        if (a == b) {
            return a.mapIndexed { i, t -> DiffLine(DiffKind.CONTEXT, i + 1, i + 1, t) }
        }

        // Trim the common prefix/suffix: Myers only needs to run on the
        // differing middle, which keeps it fast and the result tight.
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre &&
            a[a.size - 1 - suf] == b[b.size - 1 - suf]
        ) {
            suf++
        }

        val midA = a.subList(pre, a.size - suf)
        val midB = b.subList(pre, b.size - suf)
        val ops = myers(midA, midB)

        val out = ArrayList<DiffLine>(a.size + b.size)
        for (i in 0 until pre) out.add(DiffLine(DiffKind.CONTEXT, i + 1, i + 1, a[i]))
        var oa = pre
        var nb = pre
        for (op in ops) {
            when (op) {
                is Op.Equal -> {
                    for (i in 0 until op.count) {
                        out.add(DiffLine(DiffKind.CONTEXT, oa + 1, nb + 1, midA[op.start + i]))
                        oa++
                        nb++
                    }
                }
                is Op.Delete -> {
                    for (i in 0 until op.count) {
                        out.add(DiffLine(DiffKind.REMOVED, oa + 1, null, midA[op.start + i]))
                        oa++
                    }
                }
                is Op.Insert -> {
                    for (i in 0 until op.count) {
                        out.add(DiffLine(DiffKind.ADDED, null, nb + 1, midB[op.start + i]))
                        nb++
                    }
                }
            }
        }
        for (i in 0 until suf) {
            val li = a.size - suf + i
            out.add(DiffLine(DiffKind.CONTEXT, li + 1, li + 1, a[li]))
        }
        return out
    }

    // ---- Myers shortest edit script ----

    private sealed interface Op {
        val count: Int

        data class Equal(val start: Int, override val count: Int) : Op
        data class Delete(val start: Int, override val count: Int) : Op
        data class Insert(val start: Int, override val count: Int) : Op
    }

    private fun myers(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        if (n == 0) return if (m == 0) emptyList() else listOf(Op.Insert(0, m))
        if (m == 0) return listOf(Op.Delete(0, n))

        val max = n + m
        val offset = max
        var v = IntArray(2 * max + 1)
        val trace = ArrayList<IntArray>()
        var endD = 0
        var found = false

        outer@ for (d in 0..max) {
            trace.add(v.copyOf())
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) {
                    v[k + 1 + offset]
                } else {
                    v[k - 1 + offset] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[k + offset] = x
                if (x >= n && y >= m) {
                    endD = d
                    found = true
                    break@outer
                }
            }
        }
        check(found) { "myers failed to find an edit script" }

        // Backtrack to recover the edit script (in reverse).
        val ops = ArrayList<Op>()
        var x = n
        var y = m
        for (d in endD downTo 1) {
            val vPrev = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vPrev[k - 1 + offset] < vPrev[k + 1 + offset])) {
                k + 1
            } else {
                k - 1
            }
            val prevX = vPrev[prevK + offset]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                // Diagonal (equal) run.
                ops.add(Op.Equal(prevX, 1))
                x--
                y--
            }
            if (x == prevX) {
                ops.add(Op.Insert(prevY, 1))
                y--
            } else {
                ops.add(Op.Delete(prevX, 1))
                x--
            }
        }
        // Remaining diagonal to the origin (d = 0) is a leading equal run.
        while (x > 0 && y > 0) {
            ops.add(Op.Equal(0, 1))
            x--
            y--
        }
        ops.reverse()

        // Merge adjacent ops of the same kind into runs.
        val merged = ArrayList<Op>()
        for (op in ops) {
            val last = merged.lastOrNull()
            val compatible = when {
                op is Op.Equal && last is Op.Equal -> last.start + last.count == op.start
                op is Op.Delete && last is Op.Delete -> last.start + last.count == op.start
                op is Op.Insert && last is Op.Insert -> last.start + last.count == op.start
                else -> false
            }
            if (compatible && last != null) {
                merged[merged.size - 1] = when (last) {
                    is Op.Equal -> Op.Equal(last.start, last.count + op.count)
                    is Op.Delete -> Op.Delete(last.start, last.count + op.count)
                    is Op.Insert -> Op.Insert(last.start, last.count + op.count)
                }
            } else {
                merged.add(op)
            }
        }
        return merged
    }
}
