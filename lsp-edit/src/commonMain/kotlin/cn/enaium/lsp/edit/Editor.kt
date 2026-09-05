package cn.enaium.lsp.edit

import cn.enaium.imgui.ImDrawList
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImFont
import cn.enaium.imgui.ImGuiChildFlags
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * An ImGui-driven code editor widget with syntax highlighting, undo/redo,
 * find/replace, markers (diagnostics), hover callbacks, and LSP-style
 * tokenization hooks. Rendering and interaction are self-contained: call
 * [render] inside any ImGui window, drive text through [inputText]/
 * [queueTextInput], and observe edits via [onTextChange].
 */
class Editor(
    initialText: String = "",
    language: Language = Language.cpp,
) {
    var language: Language = language

    /** Stable suffix for widget IDs; callers may set it to avoid collisions. */
    var uniqueId: Long = 0L

    val buffer: TextBuffer = TextBuffer(initialText)

    var cursor: DocPos = DocPos(0, 0)
        private set

    var selectionAnchor: DocPos? = null
        private set

    private val undoStack = ArrayDeque<List<EditOp>>()
    private val redoStack = ArrayDeque<List<EditOp>>()

    private val lineStates = HashMap<Int, Int>()
    private val lineSpans = HashMap<Int, List<TokenSpan>>()

    // ---- layout state (updated every render) ----
    private var charWidth = 1f
    private var lineHeight = 1f
    private var gutterWidth = 4f
    private var textStartX = 0f
    private var textStartY = 0f
    private var contentWidth = 0f
    private var contentHeight = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    /**
     * When true, the next render scrolls the cursor into view. Set by
     * keyboard navigation, edits and programmatic cursor jumps; cleared
     * after one follow. Manual scrolling (wheel / scrollbar drag) never
     * sets it, so a large document can be read without the view snapping
     * back to the cursor every frame.
     */
    // Starts false: opening a document must not auto-scroll (a long first
    // line would otherwise nudge the view right/up toward the caret). The
    // first user edit or caret move sets it and follows from then on.
    private var scrollFollowRequested = false

    /**
     * Down-only scroll-follow for edits: typing must not yank the view back
     * to the cursor (e.g. typing at the top of a long file while the view is
     * scrolled down), but it should still scroll down when the cursor moves
     * below the viewport.
     */
    private var scrollFollowSoft = false
    private var viewOriginX = 0f
    private var viewOriginY = 0f
    private var viewWidth = 0f
    private var viewHeight = 0f
    private var fontGeneration = 0f

    /** Cumulative advance cache per (line,length) for cursor/mouse mapping. */
    private val lineWidthCache = HashMap<Int, FloatArray>()

    // ---- hover state ----
    private var lastHoverPos: DocPos? = null
    private var hoverStillStartTime = 0.0
    private var hoverFired = false
    private var hoverSuppressed = false

    // ---- cursor blink state ----
    private var lastDrawnCursor: DocPos? = null
    private var cursorMoveTime = 0.0

    // ---- config ----
    var cursorWidthChars: Float = 0.1f
    var cursorHeightLines: Float = 1f
    var palette: Array<Color> = EditorPalette.dark
    var tabSize: Int = 4
    var showLineNumbers: Boolean = true
    var showWhitespace: Boolean = true

    /**
     * When true, leading indentation is visualized: spaces draw as dots and
     * tabs draw as horizontal lines at the bottom of the line (a separate
     * option from [showWhitespace], which draws every whitespace run).
     */
    var showIndentGuides: Boolean = false
    var showMinimap: Boolean = true
    /** Minimap strip width in pixels. */
    var minimapWidth: Float = 96f
    /** Fixed minimap cell sizes (pixels), like ImGuiColorTextEdit. */
    var minimapRowHeight: Float = 3f
    var minimapColumnHeight: Float = 2f
    var minimapColumnWidth: Float = 1f

    /**
     * Minimap row height: the configured value, shrunk when needed so the
     * whole document fits inside the strip (VS Code style overview). Shared
     * by drawing and drag mapping so they always agree.
     */
    private fun minimapRowHeightPx(): Float {
        val rows = max(1, visibleLineCount)
        val fit = viewHeight / rows
        return if (fit >= minimapRowHeight) minimapRowHeight else fit.coerceAtLeast(1f)
    }
    var readOnly: Boolean = false
    var wrapEnabled: Boolean = false
    var markers: MutableMap<Int, EditorMarker> = LinkedHashMap()

    var isFocused: Boolean = false

    /**
     * True only when the editor's child window has actual keyboard focus
     * (a click landed on it), unlike [isFocused] which also counts hover.
     * Hosts use this to toggle platform text input without popping the
     * system IME while the mouse merely passes over the editor.
     */
    var isFocusedStrict: Boolean = false
        private set

    // ---- callbacks ----
    var onTextChange: ((List<EditOp>) -> Unit)? = null
    var onCursorChange: ((DocPos) -> Unit)? = null
    var onHover: ((DocPos) -> Unit)? = null
    var onHoverEnd: (() -> Unit)? = null

    /** Invoked when F9 toggles a breakpoint (with the 1-based line set). */
    var onBreakpointsChange: ((Set<Int>) -> Unit)? = null

    /** When set, [spansOf] delegates to this provider (e.g. LSP tokens). */
    var tokenProvider: ((Int) -> List<TokenSpan>?)? = null

    /**
     * When set, [drawText] renders inlay hints (parameter names, inferred
     * types) at the character gaps of each line. Hints do not occupy buffer
     * columns: the caret and hit-testing ignore them, and following text is
     * pushed right visually.
     */
    var inlayHintsProvider: ((Int) -> List<EditorInlayHint>?)? = null

    /**
     * Provides the code lenses for a document line (or null when none).
     * Lenses are drawn as dim clickable labels in the line's leading
     * whitespace (VS Code style); clicks report the lens's command.
     */
    var codeLensProvider: ((Int) -> List<EditorCodeLens>?)? = null

    /** Invoked when a code lens label is clicked (with its command or null). */
    var onCodeLensClick: ((EditorCodeLens) -> Unit)? = null

    /**
     * Font used to render inlay hints (typically a smaller variant of the
     * editor font). When null, hints use the current font. Widths measured
     * with [inlayHintWidth] use the same font so layout, cursor and click
     * mapping stay consistent.
     */
    var inlayHintFont: ImFont? = null

    /**
     * Font used to render and measure the text. When set, the font is pushed
     * for the whole [render] pass so glyphs, widths and the caret all agree.
     * Font fallback is done at the ImGui atlas level (the colortextedit
     * approach): build the font with a fallback font merged in
     * (`ImFontConfig(mergeMode = true)`), and missing glyphs render from the
     * fallback automatically. Null keeps the ImGui current font.
     */
    var font: ImFont? = null
    private var lastFont: ImFont? = null

    // ---- folding state ----
    /** Collapsible ranges (may nest). Set via [setFoldRanges]. */
    private var foldRanges: List<EditorFoldRange> = emptyList()

    /** Start lines of ranges currently collapsed. */
    private val collapsedStarts = HashSet<Int>()

    /**
     * Visible-line mapping, rebuilt whenever folds or the document change:
     * [visibleDocLines][visibleDocLines] maps a visible row to its document
     * line; hidden (folded) lines are simply absent. [docLineToVisible]
     * inverts it (-1 = hidden). All rendering, hit-testing, scrolling and
     * cursor movement operate on visible rows.
     */
    private var visibleDocLines = IntArray(0)
    private var docLineToVisible = IntArray(0)
    private var foldsDirty = true

    /** Visible row count (document lines minus folded lines). */
    private var visibleLineCount = 0

    /**
     * Replaces the fold ranges (e.g. from `textDocument/foldingRange`).
     * Collapse state is kept for ranges that still exist.
     */
    fun setFoldRanges(ranges: List<EditorFoldRange>) {
        foldRanges = ranges.sortedBy { it.startLine }
        val starts = foldRanges.mapTo(HashSet()) { it.startLine }
        collapsedStarts.removeAll { it !in starts }
        foldsDirty = true
    }

    /** The current fold ranges (see [setFoldRanges]). */
    fun getFoldRanges(): List<EditorFoldRange> = foldRanges

    /** True when [line] has a fold range and it is collapsed. */
    fun isLineFolded(line: Int): Boolean = line in collapsedStarts

    /** True when [line] is currently visible (not hidden by a fold). */
    fun isLineVisible(line: Int): Boolean {
        rebuildFoldsIfDirty()
        return line in 0 until docLineToVisible.size && docLineToVisible[line] >= 0
    }

    /**
     * Toggles the fold at [line] (or the innermost range containing it).
     * Returns true when a fold was toggled.
     */
    fun toggleFold(line: Int): Boolean {
        val range = foldAt(line) ?: containingFold(line) ?: return false
        if (!collapsedStarts.add(range.startLine)) {
            collapsedStarts.remove(range.startLine)
        }
        foldsDirty = true
        return true
    }

    /** Expands every fold hiding [line]. */
    fun unfoldAround(line: Int) {
        var changed = false
        for (r in foldRanges) {
            if (line in (r.startLine + 1)..r.endLine && collapsedStarts.remove(r.startLine)) {
                changed = true
            }
        }
        if (changed) foldsDirty = true
    }

    /** The fold range starting exactly at [line], or null. */
    fun foldAt(line: Int): EditorFoldRange? = foldRanges.firstOrNull { it.startLine == line }

    /** The innermost fold range containing [line] (start < line <= end). */
    private fun containingFold(line: Int): EditorFoldRange? =
        foldRanges.lastOrNull { it.startLine < line && line <= it.endLine }

    /**
     * Rebuilds [visibleDocLines]/[docLineToVisible] from the current folds
     * and buffer. A collapsed range keeps its start line visible and hides
     * the rest; nested collapsed ranges hide their inner lines too.
     */
    private fun rebuildFoldsIfDirty() {
        if (!foldsDirty) return
        foldsDirty = false
        val count = buffer.lineCount()
        docLineToVisible = IntArray(count) { -1 }
        val visible = ArrayList<Int>(count)
        var doc = 0
        while (doc < count) {
            visible.add(doc)
            docLineToVisible[doc] = visible.size - 1
            val collapsed = foldAt(doc)?.takeIf { doc in collapsedStarts }
            if (collapsed != null) {
                doc = (collapsed.endLine + 1).coerceAtMost(count)
            } else {
                doc++
            }
        }
        visibleDocLines = visible.toIntArray()
        visibleLineCount = visible.size
    }

    /** Extra drawing hook invoked after the editor content each frame. */
    var overlay: (() -> Unit)? = null

    /**
     * When set and returns true, the editor leaves Up/Down/Enter/Tab to an
     * overlay (e.g. a completion popup) instead of handling them itself, so
     * the popup owns navigation while it is open.
     */
    var keysReservedByOverlay: (() -> Boolean)? = null

    /**
     * Optional hook invoked INSIDE the editor's right-click context menu
     * (after the built-in clipboard items). Hosts append LSP actions such
     * as Go to Definition / Rename here.
     */
    var onContextMenu: (() -> Unit)? = null

    private var _findString = ""
    private var lastFindPos: DocPos? = null

    private val pendingText = StringBuilder()

    fun setText(text: String) {
        buffer.setText(text)
        cursor = DocPos(0, 0)
        selectionAnchor = null
        undoStack.clear()
        redoStack.clear()
        invalidateAll()
    }

    fun getText(): String = buffer.getText()

    fun lineCount(): Int = buffer.lineCount()

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /** Returns true when the cursor lies inside a selection range. */
    fun hasSelection(): Boolean =
        selectionAnchor != null && selectionAnchor != cursor

    /** Returns the ordered selection bounds, or null when empty. */
    fun selectionBounds(): Pair<DocPos, DocPos>? {
        val a = selectionAnchor ?: return null
        return if (a <= cursor) a to cursor else cursor to a
    }

    /** Returns the selected text (column-wise rect across lines). */
    fun getSelectedText(): String {
        val (a, b) = selectionBounds() ?: return ""
        return buffer.getSectionText(a, b)
    }

    /** Replaces the selection (or inserts at the cursor) with [text]. */
    fun replaceSelection(text: String) {
        if (readOnly) return
        val ops = ArrayList<EditOp>()
        val bounds = selectionBounds()
        if (bounds != null) {
            ops += buffer.erase(bounds.first, bounds.second)
        }
        val insertAt = bounds?.first ?: cursor
        ops += buffer.insert(insertAt, text)
        applyOps(ops, buffer.offset(insertAt, text.length), transaction = true)
        selectionAnchor = null
    }

    /** Inserts [text] at [pos] without touching the selection. */
    fun insertText(pos: DocPos, text: String) {
        if (readOnly) return
        val ops = buffer.insert(pos, text)
        applyOps(ops, buffer.offset(pos, text.length), transaction = false)
    }

    /** Erases [start, end). */
    fun eraseRange(start: DocPos, end: DocPos) {
        if (readOnly) return
        val ops = buffer.erase(start, end)
        applyOps(ops, start, transaction = false)
        selectionAnchor = null
    }

    fun undo() {
        val ops = undoStack.removeLastOrNull() ?: return
        for (op in ops.asReversed()) {
            if (op.insert) {
                // op.text may contain newlines; use the cross-line offset,
                // not the same-line index addition.
                buffer.erase(op.pos, buffer.offset(op.pos, op.text.length))
            } else {
                buffer.insert(op.pos, op.text)
            }
        }
        redoStack.addLast(ops)
        selectionAnchor = null
        cursor = ops.minOf { it.pos }.coerceAtLeast(DocPos(0, 0))
        scrollFollowSoft = true
        endHover()
        invalidateAll()
        onTextChange?.invoke(ops)
        onCursorChange?.invoke(cursor)
    }

    fun redo() {
        val ops = redoStack.removeLastOrNull() ?: return
        var end = ops.minOf { it.pos }
        for (op in ops) {
            if (op.insert) {
                buffer.insert(op.pos, op.text)
                end = buffer.offset(op.pos, op.text.length)
            } else {
                buffer.erase(op.pos, buffer.offset(op.pos, op.text.length))
            }
        }
        undoStack.addLast(ops)
        selectionAnchor = null
        cursor = end.coerceAtMost(endOfDocument())
        scrollFollowSoft = true
        endHover()
        invalidateAll()
        onTextChange?.invoke(ops)
        onCursorChange?.invoke(cursor)
    }

    fun selectAll() {
        selectionAnchor = DocPos(0, 0)
        cursor = endOfDocument()
        scrollFollowRequested = true
        endHover()
        onCursorChange?.invoke(cursor)
    }

    /** Moves the cursor, resetting any selection. */
    fun setCursor(pos: DocPos) {
        cursor = buffer.clamp(pos)
        unfoldAround(cursor.line)
        selectionAnchor = cursor
        scrollFollowRequested = true
        endHover()
        onCursorChange?.invoke(cursor)
    }

    /** Moves the cursor, keeping an existing selection anchor. */
    fun setCursorWithAnchor(pos: DocPos) {
        cursor = buffer.clamp(pos)
        unfoldAround(cursor.line)
        if (selectionAnchor == null) selectionAnchor = cursor
        scrollFollowRequested = true
        endHover()
        onCursorChange?.invoke(cursor)
    }

    fun endOfDocument(): DocPos =
        DocPos(buffer.lineCount() - 1, buffer.line(buffer.lineCount() - 1).length)

    fun getFindString(): String = _findString

    fun setFindString(text: String) {
        _findString = text
        lastFindPos = null
        invalidateAll()
    }

    /** Selects the next occurrence of [findText]; wraps around. */
    fun selectNextOccurrence(caseSensitive: Boolean = true): Boolean {
        if (_findString.isEmpty()) return false
        val needle = _findString
        val text = buffer.getText()
        val from = charOffsetOf(cursor)
        val start = indexOf(text, needle, from, caseSensitive)
            ?: indexOf(text, needle, 0, caseSensitive)
            ?: return false
        val s = docPosOf(start)
        val e = docPosOf(start + needle.length)
        selectionAnchor = s
        cursor = e
        lastFindPos = s
        endHover()
        onCursorChange?.invoke(cursor)
        return true
    }

    private fun indexOf(text: String, needle: String, from: Int, caseSensitive: Boolean): Int? {
        if (from > text.length) return null
        val t = if (caseSensitive) text else text.lowercase()
        val n = if (caseSensitive) needle else needle.lowercase()
        val idx = t.indexOf(n, from)
        return if (idx < 0) null else idx
    }

    private fun charOffsetOf(pos: DocPos): Int {
        var off = 0
        for (l in 0 until pos.line) {
            off += buffer.line(l).length + 1
        }
        return off + pos.index
    }

    private fun docPosOf(offset: Int): DocPos {
        var off = offset
        for (l in 0 until buffer.lineCount()) {
            val len = buffer.line(l).length
            if (off <= len) return DocPos(l, off)
            off -= len + 1
        }
        return endOfDocument()
    }

    /** Immediately inserts [text] at the cursor/selection. */
    fun inputText(text: String) {
        if (text.isEmpty() || readOnly) return
        replaceSelection(text)
    }

    /**
     * Queues [text] for insertion at the next render. The host event loop
     * calls this for text input so that typing and mouse clicks land in the
     * same frame; [render] flushes the queue after input processing.
     */
    fun queueTextInput(text: String) {
        if (text.isEmpty() || readOnly) return
        pendingText.append(text)
    }

    private fun flushPendingText() {
        if (pendingText.isEmpty()) return
        val text = pendingText.toString()
        pendingText.clear()
        replaceSelection(text)
    }

    // ---- debug state ----
    /** Lines with breakpoints (1-based adapter lines). */
    private var breakpoints: Set<Int> = emptySet()

    /** The line currently stopped on (1-based adapter line), or null. */
    private var executionLine: Int? = null

    /** The current breakpoint lines (1-based). */
    fun getBreakpointLines(): Set<Int> = breakpoints

    /** Replaces the breakpoint set (1-based lines) shown in the gutter. */
    fun setBreakpoints(lines: Set<Int>) {
        breakpoints = lines
    }

    /** True when [line] (0-based) has a breakpoint. */
    fun hasBreakpoint(line: Int): Boolean = (line + 1) in breakpoints

    /** Toggles the breakpoint at [line] (0-based); returns the new state. */
    fun toggleBreakpoint(line: Int): Boolean {
        val newSet = breakpoints.toMutableSet()
        val b = line + 1
        val added = if (b in newSet) {
            newSet.remove(b)
            false
        } else {
            newSet.add(b)
            true
        }
        breakpoints = newSet
        return added
    }

    /** Sets the debugger's current execution line (1-based), or null. */
    fun setExecutionLine(line: Int?) {
        executionLine = line
    }

    /** The debugger's current execution line (1-based), or null. */
    fun getExecutionLine(): Int? = executionLine

    fun invalidateAll() {
        lineStates.clear()
        lineSpans.clear()
        lineWidthCache.clear()
        foldsDirty = true
    }

    /**
     * Renders the editor and handles mouse/keyboard input. Returns whether
     * the editor currently has focus. Must be called inside an ImGui window.
     */
    fun render(
        title: String,
        size: ImVec2 = ImVec2(-1f, -1f),
        childFlags: Int = ImGuiChildFlags.BORDERS,
        windowFlags: Int = 0,
    ): Boolean {
        val id = if (title.isEmpty()) "##editor$uniqueId" else title
        ImGui.beginChild(id, size, childFlags, windowFlags or ImGuiWindowFlags.HORIZONTAL_SCROLLBAR)
        val pushedFont = font
        if (pushedFont != null) ImGui.pushFont(pushedFont)

        isFocusedStrict = ImGui.isWindowFocused()
        isFocused = isFocusedStrict || ImGui.isWindowHovered()
        if (isFocused) {
            ImGui.setNextFrameWantCaptureKeyboard(true)
            ImGui.setNextFrameWantCaptureMouse(false)
        }

        measure()

        // Capture the whole content area so mouse presses/drags select text
        // instead of moving the parent window (ImGui drags windows from any
        // body pixel when no item is active). Sized to the full content —
        // not the viewport — so hit-testing keeps working after scrolling.
        // ImGuiButtonFlags_NoNavFocus (1 << 10): keep the button out of
        // keyboard-navigation focus, otherwise imgui paints a NavHighlight
        // rect over the whole content area.
        ImGui.invisibleButton(
            "##editorCapture$uniqueId",
            ImVec2(contentWidth, contentHeight),
            NO_NAV_FOCUS,
        )
        val captureHovered = ImGui.isItemHovered()
        val captureActive = ImGui.isItemActive()

        handleMouse(captureHovered, captureActive)
        // Keyboard input follows the same rule as text input: the editor is
        // "active" when the window is focused OR the mouse hovers it. A
        // strict focus check breaks Enter/arrows when window focus lands on
        // the parent window after a click.
        if (isFocused) handleKeyboard()
        flushPendingText()
        // Scroll-follow only after keyboard navigation / edits / jumps;
        // manual scrolling is never overridden, so large documents stay
        // readable instead of snapping back to the cursor every frame.
        // Edits use the soft (down-only) follow so typing above the
        // viewport never yanks the scroll position.
        if (scrollFollowRequested || scrollFollowSoft) {
            ensureCursorVisible(followUp = scrollFollowRequested)
            scrollFollowRequested = false
            scrollFollowSoft = false
        }
        drawText()

        renderContextMenu()
        overlay?.invoke()

        // Extend the child window's scrollable region to the content size.
        // The dummy sits one pixel INSIDE the content rect: a 1x1 dummy at
        // exactly contentHeight would extend the scroll range by 1px and
        // leave a tiny always-scrollable strip at the bottom.
        ImGui.setCursorPos(ImVec2(contentWidth, (contentHeight - 1f).coerceAtLeast(0f)))
        ImGui.dummy(ImVec2(1f, 1f))

        if (pushedFont != null) ImGui.popFont()
        ImGui.endChild()
        return isFocused
    }

    private fun measure() {
        charWidth = ImGui.calcTextSize("M").x.coerceAtLeast(1f)
        lineHeight = ImGui.getTextLineHeight().coerceAtLeast(1f)
        scrollX = ImGui.getScrollX()
        scrollY = ImGui.getScrollY()
        val avail = ImGui.getContentRegionAvail()
        // The cursor's screen position is scroll-subtracted (it points at
        // the content origin, which moves with the scroll). Add the scroll
        // back so viewOrigin / textStart describe the FIXED viewport origin;
        // the drawing code then applies the scroll exactly once. Without
        // this, every scrolled frame rendered the whole content off-screen
        // (double-subtracting the scroll).
        val origin = ImGui.getCursorScreenPos()
        viewOriginX = origin.x + scrollX
        viewOriginY = origin.y + scrollY
        viewWidth = (avail.x - if (showMinimap) minimapWidth else 0f).coerceAtLeast(0f)
        viewHeight = avail.y
        val newFontGeneration = ImGui.getFontSize()
        if (newFontGeneration != fontGeneration || font !== lastFont) {
            fontGeneration = newFontGeneration
            lastFont = font
            lineWidthCache.clear()
        }
        gutterWidth = if (showLineNumbers) {
            // Reserve room for the fold arrow at the gutter's right edge so
            // a wide line number never overlaps it.
            8f + charWidth * max(3, buffer.lineCount().toString().length) + charWidth + 2f
        } else 4f
        textStartX = viewOriginX + gutterWidth
        textStartY = viewOriginY
        // Content spans the longest line (horizontal scroll range) and the
        // whole document vertically. Use cached real line widths when
        // available; approximate with the monospace advance otherwise, so
        // measuring a large file never triggers per-line JNI calls here.
        var maxLineWidth = 0f
        for (l in 0 until buffer.lineCount()) {
            val len = buffer.line(l).length
            val cached = lineWidthCache[l]
            val w = if (cached != null && cached.size == len + 1) cached[len] else len * charWidth
            if (w > maxLineWidth) maxLineWidth = w
        }
        contentWidth = max(avail.x, gutterWidth + maxLineWidth + 8f)
        rebuildFoldsIfDirty()
        // No bottom padding: when the lines exactly fill the viewport the
        // scroll range must be zero, otherwise the window shows a small
        // useless vertical scroll (content = viewport + padding).
        contentHeight = max(avail.y, lineHeight * visibleLineCount)
    }

    /**
     * Cumulative pixel width of [line]'s first [count] characters.
     *
     * Measured as whole-string prefixes — `calcTextSize(text.substring(0, n))`
     * — instead of summing per-character `calcTextSize` calls, so it stays in
     * lockstep with how `ImDrawList::AddText`/`RenderText` actually advances
     * glyphs. Per-character sums drift from the renderer when the font size is
     * fractional (e.g. `13f * density` with a non-integer density), because
     * `ImGui::CalcTextSize` rounds its result up and the baked advance used by
     * RenderText differs from the standalone per-glyph measurement; the drift
     * grows with line length. Prefix measurement shares the renderer's
     * accumulation path, so the cursor, mouse mapping, and drawn spans all
     * agree.
     */
    internal fun lineAdvance(line: Int, count: Int): Float {
        if (count <= 0) return 0f
        val text = buffer.line(line)
        val cached = lineWidthCache[line]
        if (cached != null && cached.size == text.length + 1) return cached[count]
        val arr = FloatArray(text.length + 1)
        val sb = StringBuilder(text.length)
        for (i in text.indices) {
            sb.append(text[i])
            arr[i + 1] = ImGui.calcTextSize(sb.toString()).x
        }
        lineWidthCache[line] = arr
        return arr[count]
    }

    /** The inlay hints of [line] (may be empty). */
    private fun inlayHintsOf(line: Int): List<EditorInlayHint> =
        inlayHintsProvider?.invoke(line) ?: emptyList()

    /** Width of [label] measured with [inlayHintFont] (or the current font). */
    private fun inlayHintWidth(label: String): Float {
        val font = inlayHintFont
        if (font != null) ImGui.pushFont(font)
        val w = ImGui.calcTextSize(label).x
        if (font != null) ImGui.popFont()
        return w
    }

    /**
     * Visual x of column [count] on [line]: the text advance plus the width
     * of every inlay hint before that column. Text is pushed right by hints
     * when drawn, so the cursor, selection, click mapping and scroll follow
     * the same visual coordinate system — hints never become "real" columns
     * the cursor can land on.
     */
    internal fun lineVisualAdvance(line: Int, count: Int): Float {
        var w = lineAdvance(line, count)
        // A hint at index k renders in the gap BEFORE character k, so column
        // k (the caret gap left of char k) sits right after that hint.
        for (h in inlayHintsOf(line)) {
            if (h.position.index <= count) w += inlayHintWidth(h.label)
        }
        return w
    }

    /**
     * Columns before [x] across [line] using the same prefix measurement as
     * [lineAdvance] (a click lands on the closer of the two glyph edges).
     * Inlay hints occupy visual space but no columns: a click on a hint
     * snaps to the nearest real text gap.
     */
    internal fun columnAtX(line: Int, x: Float): Int {
        val text = buffer.line(line)
        if (text.isEmpty()) return 0
        val arr = lineWidthCache[line]
            ?: run {
                lineAdvance(line, text.length)
                lineWidthCache[line]!!
            }
        val hints = inlayHintsOf(line).sortedBy { it.position.index }
        var hintW = 0f
        var hi = 0
        var textPrev = 0f
        for (i in text.indices) {
            while (hi < hints.size && hints[hi].position.index <= i) {
                hintW += inlayHintWidth(hints[hi].label)
                hi++
            }
            val vis = textPrev + hintW
            val w = arr[i + 1] - arr[i]
            if (x < vis + w / 2f) return i
            textPrev += w
        }
        return text.length
    }

    /**
     * Ends any pending hover: drops the tracked position and notifies the
     * host. [hoverSuppressed] stays set until the mouse actually moves to a
     * different position, so a keyboard move (or edit) does not immediately
     * re-trigger the hover tooltip for the same word under a stationary mouse.
     */
    private fun endHover() {
        if (lastHoverPos == null && !hoverFired) return
        lastHoverPos = null
        hoverFired = false
        hoverStillStartTime = 0.0
        hoverSuppressed = true
        onHoverEnd?.invoke()
    }

    /** Test hook: whether a scroll-follow is pending (see [scrollFollowRequested]). */
    internal val scrollFollowRequestedForTest: Boolean get() = scrollFollowRequested

    /** Test hook: whether a soft (down-only) scroll-follow is pending. */
    internal val scrollFollowSoftForTest: Boolean get() = scrollFollowSoft

    /** Test hook: measured content height (valid after a render). */
    internal val contentHeightForTest: Float get() = contentHeight

    /**
     * Scrolls the child window so the cursor stays visible after keyboard
     * movement or editing (scroll-follow). With [followUp] false (edits),
     * the view is only scrolled DOWN / RIGHT: typing at the top of a long
     * file while the view is scrolled down to read must not yank it back.
     * Keyboard navigation and cursor jumps pass [followUp] true and follow
     * in both directions.
     */
    private fun ensureCursorVisible(followUp: Boolean) {
        // Vertical: keep the cursor row inside the viewport.
        val cursorRow = (cursor.line.visibleRowOrNull() ?: 0).coerceIn(0, max(0, visibleLineCount - 1))
        val cursorTop = cursorRow * lineHeight
        val viewBottom = scrollY + viewHeight
        val newScrollY = when {
            cursorTop + lineHeight > viewBottom -> cursorTop + lineHeight - viewHeight
            followUp && cursorTop < scrollY -> cursorTop
            else -> scrollY
        }
        // Horizontal: keep the cursor glyph inside the viewport (visual
        // position includes inlay hints, matching what is drawn).
        val cursorLeft = lineVisualAdvance(cursor.line, cursor.index)
        val cursorRight = lineVisualAdvance(cursor.line, cursor.index) + charWidth
        val viewRight = scrollX + (viewWidth - gutterWidth)
        val newScrollX = when {
            cursorRight > viewRight -> cursorRight - (viewWidth - gutterWidth)
            followUp && cursorLeft < scrollX -> cursorLeft
            else -> scrollX
        }
        if (newScrollY != scrollY) ImGui.setScrollY(newScrollY)
        if (newScrollX != scrollX) ImGui.setScrollX(newScrollX)
        scrollX = ImGui.getScrollX()
        scrollY = ImGui.getScrollY()
    }

    private fun handleMouse(captureHovered: Boolean, captureActive: Boolean) {
        val mouse = ImGui.getMousePos()
        if (!captureHovered && !captureActive) {
            endHover()
            return
        }

        // Minimap: clicks and drags scroll the document instead of moving
        // the caret. The strip sits right of the text area; rows use the
        // fixed minimap row height and scroll together with the document.
        if (showMinimap && mouse.x >= viewOriginX + viewWidth && mouse.x < viewOriginX + viewWidth + minimapWidth) {
            val click = ImGui.isItemClicked(0)
            val drag = captureActive && ImGui.isMouseDragging(0)
            if (click || drag) {
                val rowH = minimapRowHeightPx()
                val row = ((mouse.y - viewOriginY) / rowH).toInt()
                    .coerceIn(0, max(0, visibleLineCount - 1))
                // Center the clicked line in the viewport.
                val target = (row * lineHeight - viewHeight / 2f).coerceAtLeast(0f)
                ImGui.setScrollY(target)
                endHover()
                return
            }
        }

        val x = mouse.x - textStartX + scrollX
        val y = mouse.y - textStartY + scrollY
        val row = floor(y / lineHeight).toInt().coerceIn(0, max(0, visibleLineCount - 1))
        val line = visibleDocLines.getOrElse(row) { buffer.lineCount() - 1 }
        val col = columnAtX(line, x)
        val pos = DocPos(line, col)
        val overGutter = mouse.x < textStartX

        val clicked = ImGui.isItemClicked(0) && !overGutter
        val dragging = captureActive && ImGui.isMouseDragging(0) && !overGutter
        val doubleClicked = ImGui.isMouseDoubleClicked(0) && captureHovered && !overGutter

        // Clicking the gutter fold marker toggles the fold for that line.
        if (overGutter && ImGui.isItemClicked(0)) {
            val fold = foldAt(line)
            if (fold != null) {
                toggleFold(line)
                endHover()
                return
            }
        }

        if (clicked) {
            if (ImGui.isKeyDown(ImGuiKey.MOD_SHIFT) && selectionAnchor != null) {
                // Shift+click: extend the selection from the current anchor
                // to the clicked position (standard editor behavior).
                cursor = pos
                endHover()
                onCursorChange?.invoke(cursor)
            } else {
                cursor = pos
                selectionAnchor = pos
                endHover()
                onCursorChange?.invoke(cursor)
            }
        } else if (dragging) {
            cursor = pos
            if (selectionAnchor == null) selectionAnchor = pos
            autoScrollWhileDragging(mouse.y)
            onCursorChange?.invoke(cursor)
        } else if (doubleClicked) {
            val (s, e) = wordBounds(pos)
            selectionAnchor = s
            cursor = e
            onCursorChange?.invoke(cursor)
        }

        // Hover: fire after the pointer rests on a position for 0.3s; each
        // position fires at most once until the mouse moves elsewhere.
        // Only hover while the pointer is on an actual (non-whitespace)
        // character: past the end of the line the column mapping reports
        // the last column, which would otherwise stretch the last word's
        // hover zone to the line end.
        val lineText = buffer.line(line)
        val onChar = pos.index < lineText.length && !lineText[pos.index].isWhitespace()
        if (!onChar) {
            endHover()
        } else {
            val hoverChanged = lastHoverPos != pos
            val now = ImGui.getTime()
            if (hoverChanged) {
                lastHoverPos = pos
                hoverStillStartTime = now
                hoverFired = false
                hoverSuppressed = false
            } else if (onHover != null && !hoverFired && !hoverSuppressed && now - hoverStillStartTime > 0.3) {
                onHover?.invoke(pos)
                hoverFired = true
            }
        }

        // Markers: line-number tooltip in the gutter, text tooltip over the
        // content.
        val marker = markers[line.coerceAtMost(buffer.lineCount() - 1)]
        if (overGutter && marker != null) {
            ImGui.beginTooltip()
            ImGui.text(marker.lineNumberTooltip ?: "line ${line + 1}")
            ImGui.endTooltip()
        } else if (!overGutter && marker != null && marker.textTooltip != null) {
            ImGui.beginTooltip()
            ImGui.text(marker.textTooltip)
            ImGui.endTooltip()
        }
    }

    /**
     * While drag-selecting, scrolling the mouse near the viewport's top or
     * bottom edge scrolls the document so the selection can extend past the
     * visible area (VS Code behavior). Uses a fixed per-frame speed.
     */
    private fun autoScrollWhileDragging(mouseY: Float) {
        val edgeZone = (lineHeight * 1.5f).coerceAtLeast(16f)
        val top = viewOriginY
        val bottom = viewOriginY + viewHeight
        val speed = lineHeight * 2f
        var deltaY = 0f
        if (mouseY < top + edgeZone) {
            deltaY = -speed
        } else if (mouseY > bottom - edgeZone) {
            deltaY = speed
        }
        if (deltaY != 0f) {
            val newScroll = (scrollY + deltaY).coerceAtLeast(0f)
            if (newScroll != scrollY) {
                ImGui.setScrollY(newScroll)
                scrollY = ImGui.getScrollY()
            }
        }
        // Horizontal: dragging near the left/right edge of the text area
        // scrolls horizontally so the selection can cross long lines.
        val mouseX = ImGui.getMousePos().x
        val left = textStartX
        val right = textStartX + (viewWidth - gutterWidth)
        var deltaX = 0f
        if (mouseX < left + edgeZone) {
            deltaX = -speed
        } else if (mouseX > right - edgeZone) {
            deltaX = speed
        }
        if (deltaX != 0f) {
            val maxX = (contentWidth - viewWidth).coerceAtLeast(0f)
            val newScroll = (scrollX + deltaX).coerceIn(0f, maxX)
            if (newScroll != scrollX) {
                ImGui.setScrollX(newScroll)
                scrollX = ImGui.getScrollX()
            }
        }
    }

    /** Returns the word (identifier) bounds around [pos], else the char. */
    private fun wordBounds(pos: DocPos): Pair<DocPos, DocPos> {
        val line = buffer.line(pos.line)
        var s = pos.index
        var e = pos.index
        while (s > 0 && isWord(line[s - 1])) s--
        while (e < line.length && isWord(line[e])) e++
        if (s == e) {
            if (e < line.length) e++
            else if (s > 0) s--
        }
        return DocPos(pos.line, s) to DocPos(pos.line, e)
    }

    private fun handleKeyboard() {
        val shift = ImGui.isKeyDown(ImGuiKey.MOD_SHIFT)
        val ctrl = ImGui.isKeyDown(ImGuiKey.MOD_CTRL)
        val alt = ImGui.isKeyDown(ImGuiKey.MOD_ALT)

        if (ctrl && ImGui.isKeyPressed(ImGuiKey.Z)) {
            if (shift) redo() else undo()
            return
        }
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.Y)) {
            redo()
            return
        }
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.C)) {
            copy()
            return
        }
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.X)) {
            cut()
            return
        }
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.V)) {
            paste()
            return
        }
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.A)) {
            selectAll()
            return
        }

        // Folding: Ctrl+Shift+[ folds the range containing the cursor,
        // Ctrl+Shift+] unfolds it (VS Code-style).
        if (ctrl && shift && ImGui.isKeyPressed(ImGuiKey.LEFT_BRACKET)) {
            val f = foldAt(cursor.line) ?: containingFold(cursor.line)
            if (f != null && !collapsedStarts.add(f.startLine)) {
                collapsedStarts.remove(f.startLine)
            }
            foldsDirty = true
            return
        }
        if (ctrl && shift && ImGui.isKeyPressed(ImGuiKey.RIGHT_BRACKET)) {
            var changed = false
            for (r in foldRanges) {
                if (cursor.line in (r.startLine + 1)..r.endLine && collapsedStarts.remove(r.startLine)) {
                    changed = true
                }
            }
            if (changed) foldsDirty = true
            return
        }

        // While an overlay (completion popup) owns navigation, arrow and
        // page keys belong to it, not to the caret.
        val overlayOwnsKeys = keysReservedByOverlay?.invoke() == true
        if (!overlayOwnsKeys) {
            if (ImGui.isKeyPressed(ImGuiKey.LEFT_ARROW)) move(shift) { prevChar(it) }
            if (ImGui.isKeyPressed(ImGuiKey.RIGHT_ARROW)) move(shift) { nextChar(it) }
            if (ImGui.isKeyPressed(ImGuiKey.UP_ARROW)) move(shift) { up(it) }
            if (ImGui.isKeyPressed(ImGuiKey.DOWN_ARROW)) move(shift) { down(it) }
            if (ImGui.isKeyPressed(ImGuiKey.HOME)) move(shift) { DocPos(it.line, 0) }
            if (ImGui.isKeyPressed(ImGuiKey.END)) move(shift) { endOfLine(it) }
            if (ImGui.isKeyPressed(ImGuiKey.PAGE_UP)) move(shift) { page(it, -1) }
            if (ImGui.isKeyPressed(ImGuiKey.PAGE_DOWN)) move(shift) { page(it, 1) }
        }

        // Debugging: F9 toggles a breakpoint at the cursor line.
        if (ImGui.isKeyPressed(ImGuiKey.F9)) {
            toggleBreakpoint(cursor.line)
            onBreakpointsChange?.invoke(getBreakpointLines())
            return
        }

        if (ImGui.isKeyPressed(ImGuiKey.BACKSPACE) && !readOnly) deleteLeft()
        if (ImGui.isKeyPressed(ImGuiKey.DELETE) && !readOnly) deleteRight()
        if (ImGui.isKeyPressed(ImGuiKey.ENTER) && !readOnly && !overlayOwnsKeys) {
            val indent = indentOf(cursor.line)
            replaceSelection("\n$indent")
        }
        if (ImGui.isKeyPressed(ImGuiKey.TAB) && !readOnly && !overlayOwnsKeys) {
            val pad = " ".repeat(tabSize)
            if (shift) {
                // Shift+Tab: unindent selected lines (or the current line).
                val bounds = selectionBounds()
                val first = bounds?.first?.line ?: cursor.line
                val last = bounds?.second?.line ?: cursor.line
                val ops = ArrayList<EditOp>()
                for (l in last downTo first) {
                    val cut = min(tabSize, leadingWhitespaceCount(buffer.line(l)))
                    if (cut > 0) ops += buffer.erase(DocPos(l, 0), DocPos(l, cut))
                }
                if (ops.isNotEmpty()) {
                    applyOps(ops, cursor, transaction = true)
                    onTextChange?.invoke(ops)
                }
            } else {
                replaceSelection(pad)
            }
            return
        }
    }

    private fun move(shift: Boolean, step: (DocPos) -> DocPos) {
        val next = step(cursor)
        if (next == cursor) return
        unfoldAround(next.line)
        endHover()
        if (shift) {
            val anchor = selectionAnchor ?: cursor
            cursor = next
            selectionAnchor = anchor
            scrollFollowRequested = true
            onCursorChange?.invoke(cursor)
        } else {
            setCursor(next)
        }
    }

    private fun leadingWhitespaceCount(s: String): Int {
        var i = 0
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        return i
    }

    private fun indentOf(line: Int): String =
        buffer.line(line).substring(0, leadingWhitespaceCount(buffer.line(line)))

    private fun prevChar(p: DocPos): DocPos =
        if (p.index > 0) DocPos(p.line, p.index - 1)
        else if (p.line > 0) DocPos(p.line - 1, buffer.line(p.line - 1).length)
        else p

    private fun nextChar(p: DocPos): DocPos =
        if (p.index < buffer.line(p.line).length) DocPos(p.line, p.index + 1)
        else if (p.line < buffer.lineCount() - 1) DocPos(p.line + 1, 0)
        else p

    private fun up(p: DocPos): DocPos {
        val row = p.line.visibleRowOrNull() ?: 0
        if (row <= 0) return p
        val upperLine = visibleDocLines[row - 1]
        return DocPos(upperLine, min(p.index, buffer.line(upperLine).length))
    }

    private fun down(p: DocPos): DocPos {
        val row = p.line.visibleRowOrNull() ?: 0
        if (row >= visibleLineCount - 1) return p
        val lowerLine = visibleDocLines[row + 1]
        return DocPos(lowerLine, min(p.index, buffer.line(lowerLine).length))
    }

    private fun endOfLine(p: DocPos): DocPos {
        val text = buffer.line(p.line)
        if (p.index >= text.length && p.line < buffer.lineCount() - 1) {
            return DocPos(p.line + 1, 0)
        }
        return DocPos(p.line, text.length)
    }

    private fun page(p: DocPos, dir: Int): DocPos {
        val rowsVisible = floor(contentHeight / lineHeight).toInt().coerceAtLeast(1)
        val row = p.line.visibleRowOrNull() ?: 0
        val targetRow = (row + dir * rowsVisible).coerceIn(0, max(0, visibleLineCount - 1))
        val targetLine = visibleDocLines.getOrElse(targetRow) { p.line }
        return DocPos(targetLine, min(p.index, buffer.line(targetLine).length))
    }

    private fun deleteLeft() {
        val bounds = selectionBounds()
        if (bounds != null) {
            eraseRange(bounds.first, bounds.second)
            return
        }
        val prev = prevChar(cursor)
        if (prev == cursor) return
        eraseRange(prev, cursor)
    }

    private fun deleteRight() {
        val bounds = selectionBounds()
        if (bounds != null) {
            eraseRange(bounds.first, bounds.second)
            return
        }
        val next = nextChar(cursor)
        if (next == cursor) return
        eraseRange(cursor, next)
    }

    fun copy() {
        if (hasSelection()) {
            ImGui.setClipboardText(getSelectedText())
        } else {
            ImGui.setClipboardText(buffer.line(cursor.line))
        }
    }

    fun cut() {
        if (!hasSelection()) selectLineAt(cursor.line)
        val text = getSelectedText()
        if (text.isNotEmpty()) {
            ImGui.setClipboardText(text)
            replaceSelection("")
        }
    }

    private fun selectLineAt(line: Int) {
        selectionAnchor = DocPos(line, 0)
        cursor = DocPos(line, buffer.line(line).length)
    }

    fun paste() {
        val text = ImGui.getClipboardText() ?: return
        replaceSelection(text)
    }

    private fun applyOps(ops: List<EditOp>, endAt: DocPos, transaction: Boolean) {
        if (ops.isEmpty()) return
        val singleTyping = ops.size == 1 && ops[0].insert &&
            ops[0].text.length == 1 && !ops[0].text.contains('\n')
        val last = undoStack.lastOrNull()
        if (singleTyping && last != null && last.isNotEmpty()) {
            val lastOp = last.last()
            if (lastOp.insert && lastOp.text.length == 1 && !lastOp.text.contains('\n') &&
                lastOp.pos.plus(lastOp.text.length) == ops[0].pos
            ) {
                // Merge consecutive single-character insertions into one undo step.
                undoStack[undoStack.size - 1] = last + ops
                redoStack.clear()
                cursor = buffer.clamp(endAt)
                scrollFollowSoft = true
                invalidateFrom(ops.minOf { it.pos.line })
                onTextChange?.invoke(ops)
                onCursorChange?.invoke(cursor)
                return
            }
        }
        undoStack.addLast(ops)
        redoStack.clear()
        cursor = buffer.clamp(endAt)
        scrollFollowSoft = true
        endHover()
        val firstLine = ops.minOf { it.pos.line }
        invalidateFrom(firstLine)
        onTextChange?.invoke(ops)
        onCursorChange?.invoke(cursor)
    }

    /** Drops cached tokenization for [line] and everything after it. */
    private fun invalidateFrom(line: Int) {
        lineStates.keys.removeAll { it >= line }
        lineSpans.keys.removeAll { it >= line }
        lineWidthCache.keys.removeAll { it >= line }
        foldsDirty = true
    }

    /**
     * Returns the color spans for [line], using the token provider when set.
     * Carry state is rebuilt lazily from the nearest cached earlier line, so
     * multi-line comments/strings stay correct after edits anywhere above.
     */
    fun spansOf(line: Int): List<TokenSpan> {
        // When an external token provider (e.g. LSP semantic tokens) is
        // installed, it owns highlighting entirely: lines without tokens
        // render as plain text. Falling back to the built-in regex
        // highlighter would paint keywords/strings with editor colors that
        // conflict with the server's tokens.
        val provider = tokenProvider
        if (provider != null) return provider(line) ?: emptyList()
        lineSpans[line]?.let { return it }

        // Find the nearest earlier line whose carry state is still cached,
        // then re-tokenize forward from it (bounded by the visible range).
        var start = line
        var state = 0
        while (start > 0) {
            val st = lineStates[start - 1]
            if (st != null) {
                state = st
                break
            }
            start--
        }
        for (l in start..line) {
            val result = SyntaxHighlighter.tokenize(language, buffer.line(l), state)
            lineStates[l] = result.carryState
            lineSpans[l] = result.spans
            state = result.carryState
        }
        return lineSpans[line]!!
    }

    // ==================== Context menu ====================

    /** Renders the editor's right-click menu (clipboard + host extensions). */
    private fun renderContextMenu() {
        if (!ImGui.beginPopupContextWindow("##editorCtx$uniqueId", 0)) return
        val hasSel = hasSelection()
        if (ImGui.menuItem("Cut", "Ctrl+X", false, hasSel)) cut()
        if (ImGui.menuItem("Copy", "Ctrl+C", false, hasSel)) copy()
        if (ImGui.menuItem("Paste", "Ctrl+V")) paste()
        ImGui.separator()
        if (ImGui.menuItem("Select All", "Ctrl+A")) selectAll()
        ImGui.separator()
        if (ImGui.menuItem("Undo", "Ctrl+Z", false, canUndo())) undo()
        if (ImGui.menuItem("Redo", "Ctrl+Shift+Z", false, canRedo())) redo()
        onContextMenu?.invoke()
        ImGui.endPopup()
    }

    // ==================== Drawing ====================

    private fun drawText() {
        rebuildFoldsIfDirty()
        val drawList = ImGui.getWindowDrawList()
        val origin = ImVec2(viewOriginX, viewOriginY)
        val width = viewWidth
        val height = viewHeight
        val firstRow = floor(scrollY / lineHeight).toInt().coerceIn(0, max(0, visibleLineCount - 1))
        val lastRow = floor((scrollY + height) / lineHeight).toInt().coerceIn(firstRow, max(0, visibleLineCount - 1))

        // Background + current-line highlight.
        drawList.DrawRectFilled(
            ImVec2(origin.x, origin.y),
            ImVec2(origin.x + width, origin.y + height),
            palette[PaletteIndex.BACKGROUND].toImGuiColor(),
        )
        // Debugger execution line has priority over the current-line tint.
        val execRow = executionLine?.let { (it - 1).visibleRowOrNull() }
        val currentLine = cursor.line
        val currentRow = currentLine.visibleRowOrNull()
        val highlightRow = execRow ?: currentRow
        if (highlightRow != null && highlightRow in firstRow..lastRow) {
            val y = textStartY + (highlightRow - firstRow) * lineHeight - (scrollY % lineHeight)
            drawList.DrawRectFilled(
                ImVec2(origin.x, y),
                ImVec2(origin.x + width, y + lineHeight),
                if (execRow != null) {
                    palette[PaletteIndex.EXECUTION_LINE].toImGuiColor()
                } else {
                    palette[PaletteIndex.CURRENT_LINE_BG].toImGuiColor()
                },
            )
        }

        // Gutter background: a solid strip behind the line numbers so they
        // stay readable over any content (VS Code style). The current-line
        // row gets the line-number-selected tint.
        if (showLineNumbers) {
            val gutterBg = palette[PaletteIndex.CURRENT_LINE_FILL].toImGuiColor()
            drawList.DrawRectFilled(
                ImVec2(origin.x, origin.y),
                ImVec2(origin.x + gutterWidth, origin.y + height),
                gutterBg,
            )
        }

        // Gutter with line numbers, fold markers + diagnostics markers.
        if (showLineNumbers) {
            for (row in firstRow..lastRow) {
                val line = visibleDocLines[row]
                val marker = markers[line]
                val numY = textStartY + (row - firstRow) * lineHeight - (scrollY % lineHeight)
                val color = if (line == currentLine) {
                    palette[PaletteIndex.LINE_NUMBER_SELECTED]
                } else marker?.lineNumberColor ?: palette[PaletteIndex.LINE_NUMBER]
                drawList.DrawText(
                    ImVec2(origin.x + 4f, numY),
                    "${line + 1}",
                    color.toImGuiColor(),
                )
                // Breakpoint dot at the left edge of the gutter.
                if (hasBreakpoint(line)) {
                    drawList.DrawCircleFilled(
                        ImVec2(origin.x + 3f, numY + lineHeight / 2f),
                        charWidth * 0.38f,
                        palette[PaletteIndex.BREAKPOINT].toImGuiColor(),
                    )
                }
                // Fold marker at the right edge of the gutter: an arrow that
                // points down when expanded (click to fold) and right when
                // collapsed (click to unfold). ASCII glyphs so any monospace
                // font renders them.
                if (foldAt(line) != null) {
                    val folded = line in collapsedStarts
                    drawList.DrawText(
                        ImVec2(origin.x + gutterWidth - charWidth - 2f, numY),
                        if (folded) ">" else "v",
                        palette[PaletteIndex.LINE_NUMBER].toImGuiColor(),
                    )
                }
            }
        }

        // Text + selection. Spans may be stale (semantic tokens from before
        // the last edit), so clamp them to the current line length.
        // The text region is clipped to start at the gutter's right edge:
        // horizontal scrolling must never paint code over the line-number
        // column (the gutter background stays visible as a solid strip).
        val selection = selectionBounds()
        val wsColor = palette[PaletteIndex.LINE_NUMBER].toImGuiColor()
        ImGui.pushClipRect(
            ImVec2(textStartX, origin.y),
            ImVec2(origin.x + width, origin.y + height),
            true,
        )
        for (row in firstRow..lastRow) {
            val line = visibleDocLines[row]
            val text = buffer.line(line)
            // Code lenses render in the line's leading whitespace, before
            // the text (VS Code style): dim clickable labels.
            val lenses = codeLensProvider?.invoke(line)
            if (!lenses.isNullOrEmpty()) {
                val ly = textStartY + (row - firstRow) * lineHeight - (scrollY % lineHeight)
                val mx = ImGui.getMousePos()
                var lx = textStartX - scrollX
                for (lens in lenses) {
                    val w = ImGui.calcTextSize(lens.title).x
                    val hovered = ImGui.isWindowHovered() &&
                        mx.x >= lx && mx.x <= lx + w &&
                        mx.y >= ly && mx.y <= ly + lineHeight
                    if (hovered) {
                        drawList.DrawRectFilled(
                            ImVec2(lx, ly),
                            ImVec2(lx + w, ly + lineHeight),
                            0x2A569CD6.toInt(), // translucent selection tint
                        )
                    }
                    drawList.DrawText(
                        ImVec2(lx, ly),
                        lens.title,
                        palette[PaletteIndex.INLAY_HINT].toImGuiColor(),
                    )
                    if (hovered && ImGui.isMouseClicked(0)) {
                        onCodeLensClick?.invoke(lens)
                    }
                    lx += w + charWidth * 0.6f
                }
            }
            if (text.isEmpty()) continue
            val y = textStartY + (row - firstRow) * lineHeight - (scrollY % lineHeight)

            // Indent guides: leading spaces as dots, leading tabs as
            // horizontal lines at the bottom of the line.
            if (showIndentGuides) {
                var gi = 0
                while (gi < text.length && (text[gi] == ' ' || text[gi] == '\t')) {
                    val gx = textStartX - scrollX + lineAdvance(line, gi)
                    if (text[gi] == ' ') {
                        drawList.DrawCircleFilled(
                            ImVec2(gx + charWidth / 2f, y + lineHeight / 2f),
                            charWidth * 0.12f,
                            wsColor,
                        )
                    } else {
                        // Tab: line from this column to the next tab stop.
                        val tabEnd = textStartX - scrollX + lineAdvance(line, gi + 1)
                        drawList.DrawLine(
                            ImVec2(gx, y + lineHeight - 2f),
                            ImVec2(tabEnd - 1f, y + lineHeight - 2f),
                            wsColor,
                            1f,
                        )
                    }
                    gi++
                }
            }

            val spans = spansOf(line)
            val len = text.length

            var x = textStartX - scrollX
            val hints = (inlayHintsProvider?.invoke(line) ?: emptyList())
                .sortedBy { it.position.index }
            var hintIdx = 0

            // Unified breakpoint walk: span boundaries and hint positions,
            // so every hint renders at its exact gap with text pushed right.
            val breakpoints = buildList {
                for (span in spans) {
                    add(span.start.coerceIn(0, len))
                    add(span.end.coerceIn(0, len))
                }
                for (h in hints) add(h.position.index.coerceIn(0, len))
                add(len)
            }.distinct().sorted()

            var pos = 0
            for (bp in breakpoints) {
                if (bp <= pos) continue
                // Inlay hints before this breakpoint render at the gap.
                while (hintIdx < hints.size && hints[hintIdx].position.index < bp) {
                    x = drawInlayHint(drawList, hints[hintIdx], x, y)
                    hintIdx++
                }
                // Text chunk [pos, bp) with the span palette covering bp.
                // A marker's textColor (e.g. unified-diff red/green) wins
                // over syntax colors so whole lines can be tinted.
                if (pos < bp) {
                    val span = spans.firstOrNull { pos >= it.start && bp <= it.end }
                    val base = palette[span?.palette ?: PaletteIndex.TEXT]
                    val color = markers[line]?.textColor ?: base
                    x += drawSegment(drawList, line, text, pos, bp, x, y, color, selection)
                }
                pos = bp
            }
            while (hintIdx < hints.size) {
                x = drawInlayHint(drawList, hints[hintIdx], x, y)
                hintIdx++
            }
            // Ellipsis when the line is a collapsed fold start.
            if (line in collapsedStarts) {
                drawList.DrawText(
                    ImVec2(x + charWidth * 0.5f, y),
                    "...",
                    palette[PaletteIndex.INLAY_HINT].toImGuiColor(),
                )
            }
        }
        ImGui.popClipRect()

        // Cursor — blinks while focused, solid otherwise. The blink phase
        // restarts whenever the cursor moves, so it is fully visible right
        // after a movement and only starts cycling once it settles.
        val time = ImGui.getTime()
        if (cursor != lastDrawnCursor) {
            lastDrawnCursor = cursor
            cursorMoveTime = time
        }
        val blinkOn = ((time - cursorMoveTime) % 1.0) < 0.5
        if (blinkOn || !isFocused) {
            val cl = cursor.line
            val row = cl.visibleRowOrNull()
            if (row != null && row in firstRow..lastRow) {
                // Position from real glyph advances so the cursor tracks the
                // text exactly even with proportional fonts; inlay hints push
                // it right like the rendered text.
                val x = textStartX - scrollX + lineVisualAdvance(cl, cursor.index)
                val y = textStartY + (row - firstRow) * lineHeight - (scrollY % lineHeight)
                val w = charWidth * cursorWidthChars.coerceIn(0f, 1f)
                val h = lineHeight * cursorHeightLines.coerceIn(0f, 1f)
                drawList.DrawRectFilled(
                    ImVec2(x, y),
                    ImVec2(x + w, y + h),
                    palette[PaletteIndex.CURSOR].toImGuiColor(),
                )
            }
        }

        // ==================== Minimap ====================
        // A fixed-size scaled overview of the whole document (colortextedit
        // style): each visible line gets a fixed row height, token colors
        // are drawn as per-column blocks, and a translucent viewport window
        // tracks the visible range. The strip scrolls independently of the
        // text. Clicking/dragging scrolls (see handleMouse).
        if (showMinimap && minimapWidth > 0f && minimapRowHeight > 0f) {
            val mmX = origin.x + width
            val mmY = origin.y
            val mmW = minimapWidth
            val mmH = height
            // Minimap strip background.
            drawList.DrawRectFilled(
                ImVec2(mmX, mmY),
                ImVec2(mmX + mmW, mmY + mmH),
                palette[PaletteIndex.BACKGROUND].toImGuiColor(),
            )
            // Separator between text and minimap.
            drawList.DrawLine(
                ImVec2(mmX, mmY),
                ImVec2(mmX, mmY + mmH),
                palette[PaletteIndex.LINE_NUMBER].toImGuiColor(),
                1f,
            )

            val totalMiniRows = visibleLineCount
            // Adaptive row height: the whole document fits the strip, so the
            // minimap is a fixed overview and the indicator moves with the
            // scroll (VS Code style).
            val rowH = minimapRowHeightPx()
            val colH = minimapColumnHeight
            val colW = minimapColumnWidth.coerceAtLeast(0.5f)

            for (row in 0 until totalMiniRows) {
                val line = visibleDocLines.getOrElse(row) { continue }
                val text = buffer.line(line)
                val y = mmY + row * rowH

                // Row background: selection / marker tint first.
                val sel = selectionBounds()
                val rowBg = if (sel != null && line in sel.first.line..sel.second.line) {
                    palette[PaletteIndex.SELECTION].toImGuiColor()
                } else {
                    markers[line]?.lineNumberColor?.toImGuiColor()
                }
                if (rowBg != null) {
                    drawList.DrawRectFilled(
                        ImVec2(mmX, y),
                        ImVec2(mmX + mmW, y + rowH),
                        rowBg,
                    )
                }

                // Per-token color blocks (one column per character).
                val spans = spansOf(line)
                var col = 0
                for (span in spans) {
                    val s = span.start.coerceIn(0, text.length)
                    val e = span.end.coerceIn(s, text.length)
                    if (e <= s) continue
                    val x0 = mmX + col * colW
                    val x1 = mmX + (col + (e - s)) * colW
                    drawList.DrawRectFilled(
                        ImVec2(x0, y),
                        ImVec2(x1, y + colH),
                        palette[span.palette.coerceIn(0, PaletteIndex.COUNT - 1)].toImGuiColor(),
                    )
                    col += e - s
                }
            }

            // Viewport indicator: the visible row range within the document,
            // translucent so the colored blocks underneath stay visible.
            val indicatorTop = mmY + (firstRow * rowH)
            val indicatorH = ((lastRow - firstRow + 1) * rowH).coerceAtMost(mmH)
            drawList.DrawRectFilled(
                ImVec2(mmX, indicatorTop),
                ImVec2(mmX + mmW, indicatorTop + indicatorH),
                0x2E2E2E40, // ~18% alpha dark fill (AABBGGRR)
            )
            drawList.DrawRect(
                ImVec2(mmX, indicatorTop),
                ImVec2(mmX + mmW, indicatorTop + indicatorH),
                palette[PaletteIndex.LINE_NUMBER].toImGuiColor(),
            )
        }
    }

    /** The visible row for [line], or null when the line is folded away. */
    private fun Int.visibleRowOrNull(): Int? {
        if (this < 0 || this >= docLineToVisible.size) return null
        val r = docLineToVisible[this]
        return if (r >= 0) r else null
    }

    private companion object {
        /** ImGuiButtonFlags_NoNavFocus — keep the capture button out of nav focus. */
        const val NO_NAV_FOCUS = 1 shl 10
    }

    private fun drawSegment(
        drawList: ImDrawList,
        line: Int,
        text: String,
        start: Int,
        end: Int,
        x: Float,
        y: Float,
        color: Color,
        selection: Pair<DocPos, DocPos>?,
    ): Float {
        if (end <= start) return 0f
        val segment = text.substring(start, end)
        val w = lineAdvance(line, end) - lineAdvance(line, start)

        // Selection backing (column-wise rect).
        if (selection != null) {
            val (a, b) = selection
            if (line in a.line..b.line) {
                val selStart = if (line == a.line) a.index else 0
                val selEnd = if (line == b.line) b.index else text.length
                if (end > selStart && start < selEnd) {
                    val rectStart = max(start, selStart)
                    val rectEnd = min(end, selEnd)
                    val rx = lineVisualAdvance(line, rectStart) - lineVisualAdvance(line, start)
                    val rw = lineVisualAdvance(line, rectEnd) - lineVisualAdvance(line, rectStart)
                    drawList.DrawRectFilled(
                        ImVec2(x + rx, y),
                        ImVec2(x + rx + rw, y + lineHeight),
                        palette[PaletteIndex.SELECTION].toImGuiColor(),
                    )
                }
            }
        }

        drawList.DrawText(ImVec2(x, y), segment, color.toImGuiColor())
        return w
    }

    /**
     * Draws [hint] at [x] (its character gap) in the dim inlay style and
     * returns the x after the hint, so following text shifts right. Inlay
     * hints never affect the caret or column mapping.
     */
    private fun drawInlayHint(drawList: ImDrawList, hint: EditorInlayHint, x: Float, y: Float): Float {
        val color = palette[PaletteIndex.INLAY_HINT].toImGuiColor()
        val font = inlayHintFont
        if (font != null) ImGui.pushFont(font)
        // Center the hint vertically in the line: the hint font is smaller
        // than the editor font, so offset by half the difference.
        val dy = ((lineHeight - ImGui.getFontSize()) / 2f).coerceAtLeast(0f)
        drawList.DrawText(ImVec2(x, y + dy), hint.label, color)
        val w = inlayHintWidth(hint.label)
        if (font != null) ImGui.popFont()
        return x + w
    }

    /** Current mouse-over doc position, or null. */
    fun mouseDocPos(): DocPos? = lastHoverPos

    // ==================== Layout queries (valid during/after render) ====================

    /** Line height in pixels, as measured by the last render. */
    fun lineHeightPx(): Float = lineHeight

    /** Glyph advance in pixels (monospace assumption). */
    fun charWidthPx(): Float = charWidth

    /** Screen-space x of the caret, using real glyph advances (hint-aware). */
    fun caretScreenX(): Float = textStartX - scrollX + lineVisualAdvance(cursor.line, cursor.index)

    /**
     * Screen-space y of the caret. Mirrors the drawn caret position: the
     * visible row within the viewport plus the subpixel scroll offset, so
     * the value stays inside the viewport when the document is scrolled
     * (the completion popup anchors to it).
     */
    fun caretScreenY(): Float {
        val row = cursor.line.visibleRowOrNull()
        val firstRow = floor(scrollY / lineHeight).toInt().coerceIn(0, max(0, visibleLineCount - 1))
        val r = row ?: firstRow
        return textStartY + (r - firstRow) * lineHeight - (scrollY % lineHeight)
    }

    /** Current vertical scroll offset in pixels (valid after a render). */
    fun scrollOffsetY(): Float = scrollY

    /** Screen-space y of the viewport's top edge (valid after a render). */
    fun viewportTopY(): Float = viewOriginY
}

/**
 * Gutter marker for a line (LSP diagnostics): the line-number highlight
 * color plus optional tooltips for the gutter and the text area.
 */
data class EditorMarker(
    val lineNumberColor: Color,
    val textColor: Color? = null,
    val lineNumberTooltip: String? = null,
    val textTooltip: String? = null,
)

private fun isWord(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
