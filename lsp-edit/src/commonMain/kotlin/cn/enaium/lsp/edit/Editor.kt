package cn.enaium.lsp.edit

import cn.enaium.imgui.ImDrawList
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImFont
import cn.enaium.imgui.ImGuiChildFlags
import cn.enaium.imgui.ImGuiCol
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiKey
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * An ImGui-driven code editor widget with syntax highlighting, undo/redo,
 * find/replace, markers (diagnostics), hover callbacks, and LSP-style
 * tokenization hooks. Rendering and interaction are self-contained: call
 * [render] inside any ImGui wisndow, drive text through [inputText]/
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

    /**
     * Gap between the fold marker's strip and the first character of the code.
     * Font-relative like every other gutter metric; a fixed two pixels left
     * the toggle almost touching the text.
     */
    private fun foldMarkerGap(): Float = charWidth * 0.6f

    /** Width of the breakpoint strip at the gutter's left edge. */
    private var breakpointColumnWidth = 0f
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

    /**
     * Effective strip width for the current viewport. Capped at a quarter of
     * the available width: in a narrow pane the configured 96px would
     * otherwise consume the whole viewport (viewWidth clamps to 0 and the
     * strip covers the text entirely).
     */
    private var minimapWidthPx: Float = 0f
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

    /**
     * Invoked when a breakpoint is toggled — F9 at the cursor, or a click on
     * the line-number strip — with the 1-based line set. The host forwards it
     * to whatever owns the breakpoints (a debug session, an adapter).
     */
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

    /** Line whose collapsed-fold preview is showing (-1 = none). */
    private var foldPreviewLine = -1

    // ---- diagnostic tooltip (rendered as a window) ----
    private var markerTipText: String? = null
    private var markerTipHover = false
    private var markerTipMin: ImVec2? = null
    private var markerTipMax: ImVec2? = null
    private var markerTipHideDeadline = 0.0

    /** Whether the ellipsis was hovered this frame (set by drawText). */
    private var foldPreviewHover = false

    /** Last frame's preview rect, used to keep it open while hovered. */
    private var foldPreviewMin: ImVec2? = null
    private var foldPreviewMax: ImVec2? = null

    /** Max lines rendered in the fold-preview tooltip. */
    private val FOLD_PREVIEW_MAX_LINES = 40

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

    /** Opens/keeps the preview for [startLine] (called from drawText). */
    private fun requestFoldPreview(startLine: Int) {
        foldPreviewLine = startLine
        foldPreviewHover = true
    }

    /**
     * Collapsed-fold preview as a regular window (like the LSP hover popup):
     * the pointer can move onto it — that keeps it open — and it can be
     * resized with the mouse. Closing happens here, once the pointer is on
     * neither the ellipsis nor the popup.
     */
    private fun renderFoldPreviewWindow() {
        val line = foldPreviewLine
        if (line < 0) return
        val range = foldAt(line)
        val mouse = ImGui.getMousePos()
        val onPopup = foldPreviewMin?.let { min ->
            val max = foldPreviewMax ?: min
            // Outset: ImGui's resize border sits just outside the window
            // rect — without the padding a drag on the edge counted as
            // "pointer left" and the window vanished mid-resize.
            val pad = 10f
            mouse.x >= min.x - pad && mouse.x <= max.x + pad &&
                mouse.y >= min.y - pad && mouse.y <= max.y + pad
        } ?: false
        if (range == null || line !in collapsedStarts || (!foldPreviewHover && !onPopup)) {
            foldPreviewLine = -1
            foldPreviewMin = null
            foldPreviewMax = null
            return
        }
        // Small first size (the old tooltip grew to every hidden line);
        // APPEARING so a user resize survives until the next time it opens.
        ImGui.setNextWindowPos(ImGui.getMousePos(), cn.enaium.imgui.ImGuiCond.APPEARING)
        ImGui.setNextWindowSize(ImVec2(420f, 200f), cn.enaium.imgui.ImGuiCond.APPEARING)
        ImGui.setNextWindowSizeConstraints(
            ImVec2(240f, 80f),
            ImVec2(1600f, max(viewHeight, 240f) * 2f),
        )
        ImGui.begin(
            "##foldpreview-$uniqueId",
            null,
            ImGuiWindowFlags.NO_TITLE_BAR or
                ImGuiWindowFlags.NO_FOCUS_ON_APPEARING or ImGuiWindowFlags.NO_NAV_FOCUS,
        )
        renderFoldPreviewBody(line, range)
        val pos = ImGui.getWindowPos()
        val size = ImGui.getWindowSize()
        foldPreviewMin = pos
        foldPreviewMax = ImVec2(pos.x + size.x, pos.y + size.y)
        ImGui.end()
    }

    /** Lines a collapsed fold hides, colored with the editor's own spans. */
    private fun renderFoldPreviewBody(startLine: Int, range: EditorFoldRange) {
        val last = minOf(range.endLine, buffer.lineCount() - 1)
        var line = startLine + 1
        var shown = 0
        while (line <= last) {
            if (shown >= FOLD_PREVIEW_MAX_LINES) {
                ImGui.textDisabled("…")
                break
            }
            val text = buffer.line(line)
            if (text.isEmpty()) {
                ImGui.text(" ")
            } else {
                var pos = 0
                var first = true
                for (span in spansOf(line).sortedBy { it.start }) {
                    val s = span.start.coerceIn(0, text.length)
                    val e = span.end.coerceIn(s, text.length)
                    if (e <= pos) continue
                    if (s > pos) {
                        first = emitPreviewSegment(first, text.substring(pos, s), PaletteIndex.TEXT)
                    }
                    first = emitPreviewSegment(first, text.substring(maxOf(s, pos), e), span.palette)
                    pos = maxOf(pos, e)
                }
                if (pos < text.length) {
                    emitPreviewSegment(first, text.substring(pos), PaletteIndex.TEXT)
                }
            }
            shown++
            line++
        }
    }

    /** One colored chunk of the fold preview; returns the new "first" state. */
    private fun emitPreviewSegment(first: Boolean, segment: String, paletteIndex: Int): Boolean {
        if (segment.isEmpty()) return first
        val idx = paletteIndex.coerceIn(0, PaletteIndex.COUNT - 1)
        val color = ImGui.colorConvertU32ToFloat4(palette[idx].toImGuiColor())
        if (first) {
            ImGui.textColored(color, segment)
        } else {
            ImGui.sameLine(0f, 0f)
            ImGui.textColored(color, segment)
        }
        return false
    }

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

    /** Find/replace bar pinned to the editor's top-right corner. */
    private fun renderFindPanel() {
        if (!findVisible) return
        val panePos = ImGui.getWindowPos()
        val paneSize = ImGui.getWindowSize()
        val width = 540f
        ImGui.setNextWindowPos(
            ImVec2(panePos.x + paneSize.x - width - 26f, panePos.y + 8f),
            ImGuiCond.ALWAYS,
        )
        ImGui.setNextWindowSize(ImVec2(width, 0f), ImGuiCond.ALWAYS)
        ImGui.begin(
            "##editorFind$uniqueId",
            null,
            ImGuiWindowFlags.NO_TITLE_BAR or ImGuiWindowFlags.NO_RESIZE or
                ImGuiWindowFlags.ALWAYS_AUTO_RESIZE or ImGuiWindowFlags.NO_SCROLLBAR or
                ImGuiWindowFlags.NO_MOVE or ImGuiWindowFlags.NO_FOCUS_ON_APPEARING or
                ImGuiWindowFlags.NO_NAV_FOCUS or ImGuiWindowFlags.NO_SAVED_SETTINGS,
        )
        if (findFocusSearch) {
            ImGui.setKeyboardFocusHere()
            findFocusSearch = false
        }
        ImGui.pushItemWidth(230f)
        val typed = ImGui.inputText("##findQuery$uniqueId", findQuery) ?: findQuery
        ImGui.popItemWidth()
        val searchFocused = ImGui.isItemFocused()
        findSearchFocused = searchFocused || (ImGui.isWindowFocused() && findSearchFocused && !ImGui.isAnyItemActive())
        if (typed != findQuery) {
            findQuery = typed
            findCurrent = 0
            rescanFind()
            if (findHits.isNotEmpty()) gotoFindHit(0)
        }
        ImGui.sameLine()
        if (findToggle("Aa", findMatchCase)) {
            findMatchCase = !findMatchCase
            findCurrent = 0
            rescanFind()
        }
        ImGui.sameLine()
        if (findToggle("ab", findWholeWord)) {
            findWholeWord = !findWholeWord
            findCurrent = 0
            rescanFind()
        }
        ImGui.sameLine()
        if (findToggle(".*", findRegex)) {
            findRegex = !findRegex
            findCurrent = 0
            rescanFind()
        }
        ImGui.sameLine()
        val status = when {
            findInvalid -> "Invalid regex"
            findQuery.isEmpty() -> ""
            findHits.isEmpty() -> "No results"
            else -> "${findCurrent + 1}/${findHits.size}"
        }
        if (findInvalid) ImGui.pushStyleColor(ImGuiCol.TEXT, ImVec4(1f, 0.4f, 0.4f, 1f))
        ImGui.text(status)
        if (findInvalid) ImGui.popStyleColor()
        ImGui.sameLine()
        if (ImGui.smallButton("^##findPrev$uniqueId")) findStep(forward = false)
        ImGui.sameLine()
        if (ImGui.smallButton("v##findNext$uniqueId")) findStep(forward = true)
        ImGui.sameLine()
        if (ImGui.smallButton("x##findClose$uniqueId")) {
            closeFind()
            ImGui.end()
            return
        }
        if (findReplaceRow) {
            ImGui.pushItemWidth(230f)
            val typedReplace = ImGui.inputText("##findReplace$uniqueId", findReplacement) ?: findReplacement
            ImGui.popItemWidth()
            findReplacement = typedReplace
            ImGui.sameLine()
            if (ImGui.smallButton("Replace")) replaceCurrent()
            ImGui.sameLine()
            if (ImGui.smallButton("Replace All")) replaceAll()
        }
        if (searchFocused && ImGui.isKeyPressed(ImGuiKey.ENTER)) {
            val shift = ImGui.isKeyDown(ImGuiKey.LEFT_SHIFT) || ImGui.isKeyDown(ImGuiKey.RIGHT_SHIFT)
            findStep(forward = !shift)
        }
        if (ImGui.isKeyPressed(ImGuiKey.ESCAPE)) closeFind()
        ImGui.end()
    }

    /** Small toggle button painted like a pressed tool button when active. */
    private fun findToggle(label: String, active: Boolean): Boolean {
        if (active) ImGui.pushStyleColor(ImGuiCol.BUTTON, ImVec4(0.26f, 0.45f, 0.62f, 1f))
        val clicked = ImGui.smallButton("$label##find-$label$uniqueId")
        if (active) ImGui.popStyleColor()
        return clicked
    }

    /**
     * Set by the host while one of its own text fields owns the keyboard: the
     * editor then ignores keystrokes and queued text so they cannot modify
     * the document.
     */
    var keyboardOwnedByHost: Boolean = false

    /** Extra drawing hook invoked after the editor content each frame. */
    var overlay: (() -> Unit)? = null

    /**
     * Background highlights (search matches, rename linkage, ...). Grouped by
     * line on assignment so drawing costs O(visible rows).
     */
    var highlights: List<EditorHighlight> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            highlightsByLine = value.groupBy { it.line }
            invalidateAll()
        }

    private var highlightsByLine: Map<Int, List<EditorHighlight>> = emptyMap()

    // ==================== find / replace ====================

    /** Whether the find bar is showing. */
    var findVisible: Boolean = false
        private set

    /** Whether the replace row is part of the bar (Cmd+R vs Cmd+F). */
    var findReplaceRow: Boolean = false
        private set

    private var findQuery = ""
    private var findReplacement = ""
    private var findMatchCase = false
    private var findWholeWord = false
    private var findRegex = false
    private var findCurrent = 0
    private var findInvalid = false
    private var findFocusSearch = false
    private var findSearchFocused = false
    private var findHits: List<FindHit> = emptyList()
    private var findHighlightList: List<EditorHighlight> = emptyList()
    private var findHighlightByLine: Map<Int, List<EditorHighlight>> = emptyMap()

    /** One search hit: line and the character range [start, end). */
    private class FindHit(val line: Int, val start: Int, val end: Int, val groups: List<String>)

    // ==================== in-place rename ====================

    /** True while a symbol is being renamed in place. */
    var renameActive: Boolean = false
        private set

    /**
     * True for the frame in which a rename consumed Enter/Esc. The rename runs
     * before the editor handles keys, so on that frame [renameActive] is
     * already false — the reservation must outlive it or Enter would also be
     * inserted as a newline.
     */
    var renameConsumedKeys: Boolean = false
        private set

    /**
     * True while the change being notified comes from undo/redo. Hosts must
     * not react to it the way they react to typing: an undo is not a prefix
     * being written, so it must not open a completion popup.
     */
    var lastChangeWasHistory: Boolean = false
        private set

    /**
     * Invoked on commit with the symbol's position and the new name; the host
     * performs the LSP rename. Occurrences in this document were already
     * renamed live.
     */
    var onRenameCommit: ((line: Int, index: Int, newName: String) -> Unit)? = null

    private var renameLine = -1
    private var renameStart = 0
    private var renameOriginal = ""
    private var renameName = ""
    private var renameHits: List<Triple<Int, Int, Int>> = emptyList()
    private var renameHighlightByLine: Map<Int, List<EditorHighlight>> = emptyMap()

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

    /**
     * Applies [edits] as ONE undo step: a single undo reverts all of them
     * (used for a completion's main edit together with its auto-import
     * edits). Positions are in the document's current coordinates and the
     * edits apply in list order, so callers pass them bottom-up — earlier
     * edits must not shift later ones. [caret] places the cursor afterwards.
     * Returns true when anything was applied.
     */
    fun applyEdits(edits: List<EditorEdit>, caret: DocPos? = null): Boolean {
        if (readOnly) return false
        val useful = edits.filter { it.from != it.to || it.text.isNotEmpty() }
        if (useful.isEmpty()) return false
        val ops = ArrayList<EditOp>()
        for (e in useful) {
            if (e.from != e.to) ops += buffer.erase(e.from, e.to)
            if (e.text.isNotEmpty()) ops += buffer.insert(e.from, e.text)
        }
        if (ops.isEmpty()) return false
        applyOps(ops, caret ?: cursor, transaction = true)
        selectionAnchor = null
        return true
    }

    fun undo() {
        val ops = undoStack.removeLastOrNull() ?: return
        // The document change is the INVERSE of the recorded ops: notify
        // hosts (LSP didChange) with inverted operations, otherwise the
        // server is told the old text was inserted again and its document
        // state drifts (stale diagnostics/highlighting after undo).
        val inverse = ArrayList<EditOp>(ops.size)
        for (op in ops.asReversed()) {
            if (op.insert) {
                // op.text may contain newlines; use the cross-line offset,
                // not the same-line index addition.
                buffer.erase(op.pos, buffer.offset(op.pos, op.text.length))
            } else {
                buffer.insert(op.pos, op.text)
            }
            inverse.add(EditOp(op.pos, op.text, insert = !op.insert))
        }
        redoStack.addLast(ops)
        selectionAnchor = null
        cursor = ops.minOf { it.pos }.coerceAtLeast(DocPos(0, 0))
        scrollFollowSoft = true
        suppressHover()
        invalidateAll()
        lastChangeWasHistory = true
        try {
            onTextChange?.invoke(inverse.reversed())
        } finally {
            lastChangeWasHistory = false
        }
        onCursorChange?.invoke(cursor)
    }

    fun redo() {
        val ops = redoStack.removeLastOrNull() ?: return
        // Re-applying the recorded ops IS the forward change: notify as-is.
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
        suppressHover()
        invalidateAll()
        lastChangeWasHistory = true
        try {
            onTextChange?.invoke(ops)
        } finally {
            lastChangeWasHistory = false
        }
        onCursorChange?.invoke(cursor)
    }

    fun selectAll() {
        selectionAnchor = DocPos(0, 0)
        cursor = endOfDocument()
        scrollFollowRequested = true
        suppressHover()
        onCursorChange?.invoke(cursor)
    }

    /** Moves the cursor, resetting any selection. */
    fun setCursor(pos: DocPos) {
        cursor = buffer.clamp(pos)
        unfoldAround(cursor.line)
        selectionAnchor = cursor
        scrollFollowRequested = true
        suppressHover()
        onCursorChange?.invoke(cursor)
    }

    /** Moves the cursor, keeping an existing selection anchor. */
    fun setCursorWithAnchor(pos: DocPos) {
        cursor = buffer.clamp(pos)
        unfoldAround(cursor.line)
        if (selectionAnchor == null) selectionAnchor = cursor
        scrollFollowRequested = true
        suppressHover()
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
        suppressHover()
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

    /** True while the host should not request completions (renaming). */
    val suppressCompletion: Boolean get() = renameActive

    private fun flushPendingText() {
        // Same rule as the keyboard: text that belongs to a focused field must
        // not land in the document.
        if (keyboardOwnedByHost || (findVisible && findSearchFocused)) {
            pendingText.clear()
            return
        }
        if (pendingText.isEmpty()) return
        val text = pendingText.toString()
        pendingText.clear()
        replaceSelection(text)
    }

    // ---- debug state ----
    /** Breakpoints by 1-based line, with the state the gutter draws. */
    private var breakpoints: Map<Int, EditorBreakpoint> = emptyMap()

    /** The line currently stopped on (1-based adapter line), or null. */
    private var executionLine: Int? = null

    /** The current breakpoint lines (1-based). */
    fun getBreakpointLines(): Set<Int> = breakpoints.keys

    /**
     * Replaces the breakpoint set (1-based lines) with plain enabled markers,
     * for hosts that have no adapter state to show.
     */
    fun setBreakpoints(lines: Set<Int>) {
        breakpoints = lines.associateWith { EditorBreakpoint(line = it) }
    }

    /**
     * Replaces the markers, states included: [EditorBreakpoint.verified] from
     * a debug adapter, [EditorBreakpoint.conditional] for conditioned ones,
     * and so on. Each state gets its own icon.
     */
    fun setBreakpointMarkers(markers: List<EditorBreakpoint>) {
        breakpoints = markers.associateBy { it.line }
    }

    /** The markers with their states, ordered by line. */
    fun getBreakpointMarkers(): List<EditorBreakpoint> = breakpoints.values.sortedBy { it.line }

    /** The marker at [line] (0-based), or null. */
    fun breakpointAt(line: Int): EditorBreakpoint? = breakpoints[line + 1]

    /** True when [line] (0-based) has a breakpoint. */
    fun hasBreakpoint(line: Int): Boolean = (line + 1) in breakpoints

    /** Toggles the breakpoint at [line] (0-based); returns the new state. */
    fun toggleBreakpoint(line: Int): Boolean {
        val newSet = breakpoints.keys.toMutableSet()
        val b = line + 1
        val added = if (b in newSet) {
            newSet.remove(b)
            false
        } else {
            newSet.add(b)
            true
        }
        breakpoints = newSet.associateWith { EditorBreakpoint(line = it) }
        return added
    }

    /** Sets the debugger's current execution line (1-based), or null. */
    fun setExecutionLine(line: Int?) {
        executionLine = line
    }

    /** The debugger's current execution line (1-based), or null. */
    fun getExecutionLine(): Int? = executionLine

    /** Opens the find bar (Cmd+F); [withReplace] also shows the replace row. */
    fun openFind(withReplace: Boolean = false) {
        val selected = getSelectedText()
        if (selected.isNotEmpty() && !selected.contains('\n')) findQuery = selected
        findReplaceRow = findReplaceRow || withReplace
        findVisible = true
        findFocusSearch = true
        findCurrent = 0
        rescanFind()
    }

    /** Closes the find bar and clears its highlights. */
    fun closeFind() {
        findVisible = false
        findReplaceRow = false
        findHits = emptyList()
        findHighlightList = emptyList()
        findHighlightByLine = emptyMap()
        invalidateAll()
    }

    /** Moves to the next/previous match (wrapping). */
    fun findStep(forward: Boolean) {
        if (findHits.isEmpty()) return
        val next = if (forward) {
            (findCurrent + 1) % findHits.size
        } else {
            (findCurrent - 1 + findHits.size) % findHits.size
        }
        gotoFindHit(next)
    }

    /** Replaces the current match. */
    fun replaceCurrent() {
        val hit = findHits.getOrNull(findCurrent) ?: return
        applyEdits(
            listOf(
                EditorEdit(
                    DocPos(hit.line, hit.start),
                    DocPos(hit.line, hit.end),
                    expandFindReplacement(hit),
                ),
            ),
        )
        rescanFind()
        if (findCurrent >= findHits.size) findCurrent = 0
    }

    /** Replaces every match as one undoable edit. */
    fun replaceAll() {
        if (findHits.isEmpty()) return
        val edits = findHits.asReversed().map { hit ->
            EditorEdit(DocPos(hit.line, hit.start), DocPos(hit.line, hit.end), expandFindReplacement(hit))
        }
        applyEdits(edits)
        findCurrent = 0
        rescanFind()
    }

    /** `$n` / `${n}` expansion for regex replacements. */
    private fun expandFindReplacement(hit: FindHit): String {
        if (!findRegex) return findReplacement
        return Regex("\\$\\{(\\d+)\\}|\\$(\\d+)").replace(findReplacement) { m ->
            val idx = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: -1
            hit.groups.getOrNull(idx) ?: m.value
        }
    }

    private fun rescanFind() {
        findInvalid = false
        val hits = ArrayList<FindHit>()
        if (findQuery.isNotEmpty()) {
            val pattern = try {
                val body = if (findRegex) findQuery else Regex.escape(findQuery)
                val wrapped = if (findWholeWord) "\\b(?:$body)\\b" else body
                Regex(wrapped, if (findMatchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } catch (t: Throwable) {
                findInvalid = true
                null
            }
            if (pattern != null) {
                for (l in 0 until buffer.lineCount()) {
                    for (m in pattern.findAll(buffer.line(l))) {
                        if (m.value.isEmpty()) continue
                        hits.add(FindHit(l, m.range.first, m.range.last + 1, m.groupValues))
                    }
                }
            }
        }
        findHits = hits
        if (findCurrent >= hits.size) findCurrent = 0
        val fill = palette[PaletteIndex.SEARCH_RESULT_BG]
        val accent = palette[PaletteIndex.MATCHING_BRACKET_ACTIVE]
        val list = ArrayList<EditorHighlight>(hits.size)
        for ((i, hit) in hits.withIndex()) {
            val current = i == findCurrent
            list.add(
                EditorHighlight(
                    line = hit.line,
                    start = hit.start,
                    end = hit.end,
                    fill = if (current) withAlpha(accent, 0x66) else withAlpha(fill, 0x55),
                    border = withAlpha(accent, 0x99),
                    borderThickness = if (current) 1.5f else 1f,
                ),
            )
        }
        findHighlightList = list
        findHighlightByLine = list.groupBy { it.line }
        invalidateAll()
    }

    private fun gotoFindHit(index: Int) {
        val hit = findHits.getOrNull(index) ?: return
        findCurrent = index
        // Move the caret to the match but do NOT select it: a selection would
        // turn the next keystroke into a replacement of the match. The match
        // is shown by its highlight.
        setCursor(DocPos(hit.line, hit.start))
        scrollFollowRequested = true
        rescanFind() // refresh which match is drawn as current
    }

    /** Packed colour with [alpha] replacing its own. */
    private fun withAlpha(color: Color, alpha: Int): Color {
        return ((color and 0x00000000FFFFFFFFL) and 0x00FFFFFFL) or (alpha.toLong() shl 24)
    }

    /**
     * Starts an in-place rename of the symbol under the caret: it becomes the
     * selection, typing edits it directly, every other occurrence follows and
     * Enter commits (via [onRenameCommit]), Esc restores.
     */
    fun startRename(): Boolean {
        val line = cursor.line
        val text = buffer.line(line)
        var start = cursor.index
        var end = cursor.index
        while (start > 0 && isWord(text[start - 1])) start--
        while (end < text.length && isWord(text[end])) end++
        if (start == end) return false
        renameLine = line
        renameStart = start
        renameOriginal = text.substring(start, end)
        renameName = renameOriginal
        renameHits = renameOccurrences(renameOriginal)
        renameActive = true
        setCursor(DocPos(line, start))
        setCursorWithAnchor(DocPos(line, end))
        refreshRenameHighlights()
        return true
    }

    /** Cancels the rename, restoring every occurrence of the original name. */
    fun cancelRename() {
        if (!renameActive) return
        val line = renameLine
        val start = renameStart
        val original = renameOriginal
        val current = renameName
        renameActive = false
        renameHighlightByLine = emptyMap()
        val edits = ArrayList<EditorEdit>()
        val lineText = buffer.line(line)
        var end = start
        while (end < lineText.length && isWord(lineText[end])) end++
        if (end > start && lineText.substring(start, end) != original) {
            edits.add(EditorEdit(DocPos(line, start), DocPos(line, end), original))
        }
        if (current.isNotEmpty() && current != original) {
            for ((l, s0, e0) in renameOccurrences(current)) {
                if (l == line && s0 == start) continue
                edits.add(EditorEdit(DocPos(l, s0), DocPos(l, e0), original))
            }
        }
        if (edits.isNotEmpty()) {
            applyEdits(edits.sortedWith(compareByDescending<EditorEdit> { it.from.line }.thenByDescending { it.from.index }))
        }
        setCursor(DocPos(line, start + original.length))
        invalidateAll()
    }

    /** Whole-word occurrences of [name] as (line, start, end). */
    private fun renameOccurrences(name: String): List<Triple<Int, Int, Int>> {
        if (name.isEmpty()) return emptyList()
        val word = Regex("\\b" + Regex.escape(name) + "\\b")
        val out = ArrayList<Triple<Int, Int, Int>>()
        for (l in 0 until buffer.lineCount()) {
            for (m in word.findAll(buffer.line(l))) {
                out.add(Triple(l, m.range.first, m.range.last + 1))
            }
        }
        return out
    }

    /** The symbol being edited plus every other occurrence, as highlights. */
    private fun refreshRenameHighlights() {
        val fill = withAlpha(palette[PaletteIndex.SELECTION], 0x30)
        val border = withAlpha(palette[PaletteIndex.MATCHING_BRACKET_ACTIVE], 0x99)
        val accent = withAlpha(palette[PaletteIndex.MATCHING_BRACKET_ACTIVE], 0xCC)
        val list = ArrayList<EditorHighlight>(renameHits.size)
        for ((l, s0, e0) in renameHits) {
            val edited = l == renameLine && s0 == renameStart
            list.add(
                EditorHighlight(
                    line = l,
                    start = s0,
                    end = e0,
                    fill = fill,
                    border = if (edited) accent else border,
                    borderThickness = if (edited) 1.5f else 1f,
                ),
            )
        }
        renameHighlightByLine = list.groupBy { it.line }
        invalidateAll()
    }

    /**
     * Per-frame rename upkeep: follows the typed name into every other
     * occurrence and finishes on Enter/Esc/a caret that left the symbol.
     * Called from render(), before the keyboard is handled.
     */
    private fun updateRename() {
        // The flag only lives for the frame that set it.
        renameConsumedKeys = false
        if (!renameActive) return
        val line = renameLine
        if (line < 0 || line >= buffer.lineCount()) {
            renameActive = false
            renameHighlightByLine = emptyMap()
            return
        }
        if (ImGui.isKeyPressed(ImGuiKey.ESCAPE)) {
            cancelRename()
            renameConsumedKeys = true
            return
        }
        val lineText = buffer.line(line)
        var end = renameStart
        while (end < lineText.length && isWord(lineText[end])) end++

        // The editor hands arrows/Home/End to the overlay while renaming (the
        // session is an overlay as far as keysReservedByOverlay is concerned),
        // so the caret is moved here: VSCode lets you edit anywhere inside the
        // name.
        if (cursor.line == line) {
            var moved: Int? = null
            if (ImGui.isKeyPressed(ImGuiKey.LEFT_ARROW)) {
                moved = (cursor.index - 1).coerceAtLeast(renameStart)
            }
            if (ImGui.isKeyPressed(ImGuiKey.RIGHT_ARROW)) {
                moved = (cursor.index + 1).coerceAtMost(end)
            }
            if (ImGui.isKeyPressed(ImGuiKey.HOME)) moved = renameStart
            if (ImGui.isKeyPressed(ImGuiKey.END)) moved = end
            if (moved != null && moved != cursor.index) setCursor(DocPos(line, moved))
        }

        val typed = lineText.substring(renameStart, end)
        if (ImGui.isKeyPressed(ImGuiKey.ENTER)) {
            renameActive = false
            renameConsumedKeys = true
            renameHighlightByLine = emptyMap()
            invalidateAll()
            if (typed.isNotEmpty() && typed != renameOriginal) {
                onRenameCommit?.invoke(line, renameStart, typed)
            }
            return
        }
        if (cursor.line != line || cursor.index < renameStart || cursor.index > end) {
            // Clicking elsewhere commits, like VSCode.
            renameActive = false
            renameConsumedKeys = true
            renameHighlightByLine = emptyMap()
            invalidateAll()
            if (typed.isNotEmpty() && typed != renameOriginal) {
                onRenameCommit?.invoke(line, renameStart, typed)
            }
            return
        }
        if (typed != renameName && typed.isNotEmpty()) {
            // Live linkage: rename every other occurrence.
            val old = renameName
            val edits = ArrayList<EditorEdit>()
            for ((l, s0, e0) in renameOccurrences(old)) {
                if (l == line && s0 == renameStart) continue
                edits.add(EditorEdit(DocPos(l, s0), DocPos(l, e0), typed))
            }
            val caret = cursor
            val delta = edits.count { it.from.line == line && it.from.index < caret.index } *
                (typed.length - old.length)
            if (edits.isNotEmpty()) {
                applyEdits(
                    edits.sortedWith(compareByDescending<EditorEdit> { it.from.line }.thenByDescending { it.from.index }),
                )
                setCursor(DocPos(caret.line, (caret.index + delta).coerceAtLeast(renameStart)))
            }
            renameName = typed
            renameHits = renameOccurrences(typed)
        }
        refreshRenameHighlights()
    }

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
        updateRename()
        if (isFocused) handleKeyboard()
        flushPendingText()
        // Scroll-follow only after keyboard navigation / edits / jumps;
        // manual scrolling is never overridden, so large documents stay
        // readable instead of snapping back to the cursor every frame.
        // Edits use the soft (down-only) follow so typing above the
        // viewport never yanks the scroll position.
        if (scrollFollowRequested || scrollFollowSoft) {
            val followUp = scrollFollowRequested
            scrollFollowRequested = false
            scrollFollowSoft = false
            // May re-arm scrollFollowSoft when ImGui clamped the target
            // (content grew this frame; the scroll max updates next frame).
            ensureCursorVisible(followUp = followUp)
        }
        markerTipHover = false
        drawText()
        renderMarkerTipWindow()
        renderFindPanel()
        renderFoldPreviewWindow()

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
        minimapWidthPx = if (showMinimap) minOf(minimapWidth, avail.x * 0.25f) else 0f
        viewWidth = (avail.x - minimapWidthPx).coerceAtLeast(0f)
        viewHeight = avail.y
        val newFontGeneration = ImGui.getFontSize()
        if (newFontGeneration != fontGeneration || font !== lastFont) {
            fontGeneration = newFontGeneration
            lastFont = font
            lineWidthCache.clear()
        }
        // The gutter is a breakpoint column, then the line numbers, then the
        // fold arrow. The breakpoint column is its own strip so a marker never
        // lands on a digit, and it is as wide as a row is tall (the icons are
        // square and scale with the font).
        breakpointColumnWidth = if (showLineNumbers) lineHeight else 0f
        gutterWidth = if (showLineNumbers) {
            breakpointColumnWidth +
                // padding + numbers + the fold arrow's strip at the right edge
                // + the gap between that strip and the code
                8f + charWidth * max(3, buffer.lineCount().toString().length) +
                charWidth + foldMarkerGap()
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
        // This drives ImGui's horizontal scroll range through the capture
        // item. The visible text area is viewWidth (the minimap strip is
        // excluded from it), so pad by the strip's width — otherwise
        // maxScroll = contentWidth - avail lets the line end travel under
        // the minimap, where the text clip hides it entirely (scrolling to
        // the far right could never reveal the end of a long line).
        contentWidth = minimapWidthPx + max(viewWidth, gutterWidth + maxLineWidth + 8f)
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
    /**
     * Edit/keyboard hook: drop any shown hover and suppress a new one until
     * the POINTER MOVES to another position. Unlike [endHover] it keeps
     * [lastHoverPos] so the parked mouse does not look like a move on the
     * next frame (which would immediately clear the suppression and let the
     * hover cover the text being typed).
     */
    private fun suppressHover() {
        hoverFired = false
        hoverStillStartTime = 0.0
        hoverSuppressed = true
        onHoverEnd?.invoke()
    }

    private fun endHover() {
        if (lastHoverPos == null && !hoverFired) {
            // Nothing is showing yet, but ARM the suppression anyway: an
            // edit or keypress with the mouse parked over the text must not
            // let the hover fire 0.3s later (it would cover what is being
            // typed). Cleared when the pointer moves to another position.
            hoverSuppressed = true
            return
        }
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

    /** Test hook: measured viewport height (valid after a render). */
    internal val viewHeightForTest: Float get() = viewHeight

    /**
     * Scrolls the child window so the cursor stays visible after keyboard
     * movement or editing (scroll-follow). With [followUp] false (edits),
     * the view is only scrolled DOWN / RIGHT: typing at the top of a long
     * file while the view is scrolled down to read must not yank it back.
     * Keyboard navigation and cursor jumps pass [followUp] true and follow
     * in both directions.
     */
    private fun ensureCursorVisible(followUp: Boolean) {
        // The edit that requested this follow-up ran AFTER measure(), so the
        // line<->visible-row map can be one frame stale (a new last line
        // reports row null -> treated as row 0 -> "cursor visible" -> no
        // scroll). Rebuild it first when dirty.
        rebuildFoldsIfDirty()
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
        // ImGui's scroll maximum is derived from the PREVIOUS frame's
        // content size: when an edit grows the document (e.g. a new last
        // line), the requested target is clamped for this frame. Re-arm the
        // (down-only) follow so the next frame — with the updated maximum —
        // completes the scroll.
        if (scrollY < newScrollY - 0.5f) {
            scrollFollowSoft = true
        }
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
        if (showMinimap && minimapWidthPx > 0f && mouse.x >= viewOriginX + viewWidth &&
            mouse.x < viewOriginX + viewWidth + minimapWidthPx
        ) {
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
        // The gutter does not scroll horizontally while the text does, so the
        // gutter's screen edge is the text start minus the horizontal scroll
        // (textStartX itself includes it).
        val gutterEdgeX = textStartX - scrollX
        val overGutter = mouse.x < gutterEdgeX

        val clicked = ImGui.isItemClicked(0) && !overGutter
        val dragging = captureActive && ImGui.isMouseDragging(0) && !overGutter
        val doubleClicked = ImGui.isMouseDoubleClicked(0) && captureHovered && !overGutter

        // Gutter clicks: the strip at the gutter's right edge belongs to the
        // fold marker, the breakpoint column and the line numbers to the
        // breakpoint — how IDEs split those targets. The host hears about
        // breakpoint changes through [onBreakpointsChange], e.g. to tell a
        // debug adapter.
        if (overGutter && ImGui.isItemClicked(0)) {
            val overFoldMarker = mouse.x >= gutterEdgeX - charWidth - foldMarkerGap()
            if (overFoldMarker && foldAt(line) != null) {
                toggleFold(line)
            } else {
                toggleBreakpoint(line)
                onBreakpointsChange?.invoke(getBreakpointLines())
            }
            endHover()
            return
        }

        // Clicking the ellipsis of a collapsed line unfolds it; the click
        // must not also move the caret. Hit-test in SCREEN coordinates:
        // `x` above is content-relative (mouse.x - textStartX + scrollX),
        // so comparing it against a screen-space edge never matched.
        if (ImGui.isItemClicked(0) && !overGutter && line in collapsedStarts && foldAt(line) != null) {
            val ellipsisW = ImGui.calcTextSize("...").x
            val ex = textStartX - scrollX + lineVisualAdvance(line, buffer.line(line).length) + charWidth * 0.5f
            if (mouse.x >= ex && mouse.x <= ex + ellipsisW) {
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
        // content — but only while the pointer is inside the diagnostic's
        // underlined range (same span as the squiggle), not on the whole
        // line.
        // Record the hovered diagnostic; renderMarkerTipWindow draws it as a
        // real window (the pointer can move onto it and it can be resized,
        // which a tooltip cannot).
        val marker = markers[line.coerceAtMost(buffer.lineCount() - 1)]
        if (overGutter && marker != null) {
            markerTipText = marker.lineNumberTooltip ?: "line ${line + 1}"
            markerTipHover = true
        } else if (!overGutter && marker?.textTooltip != null) {
            val ranges = marker.underlineRanges
            val insideRange = ranges.isNullOrEmpty() ||
                ranges.any { pos.index >= it.first && pos.index < it.second }
            if (insideRange) {
                markerTipText = marker.textTooltip
                markerTipHover = true
            }
        }
    }

    /**
     * Diagnostic tooltip as a regular window, like the documentation hover:
     * the pointer may move onto it (it stays open) and it can be resized.
     * Closing happens here once the pointer is on neither the squiggle nor
     * the window.
     */
    private fun renderMarkerTipWindow() {
        val text = markerTipText ?: return
        val mouse = ImGui.getMousePos()
        val onPopup = markerTipMin?.let { min ->
            val max = markerTipMax ?: min
            // Outset: ImGui's resize border sits just outside the rect.
            val pad = 10f
            mouse.x >= min.x - pad && mouse.x <= max.x + pad &&
                mouse.y >= min.y - pad && mouse.y <= max.y + pad
        } ?: false
        val now = ImGui.getTime()
        if (!markerTipHover && !onPopup) {
            if (markerTipHideDeadline == 0.0) {
                markerTipHideDeadline = now + 0.35
            } else if (now >= markerTipHideDeadline) {
                markerTipText = null
                markerTipMin = null
                markerTipMax = null
                markerTipHideDeadline = 0.0
                return
            }
        } else {
            markerTipHideDeadline = 0.0
        }
        ImGui.setNextWindowPos(ImVec2(mouse.x + 16f, mouse.y + 20f), ImGuiCond.APPEARING)
        ImGui.setNextWindowSize(ImVec2(360f, 0f), ImGuiCond.APPEARING)
        ImGui.setNextWindowSizeConstraints(
            ImVec2(200f, 0f),
            ImVec2(720f, ImGui.getIO().displaySize.y * 0.5f),
        )
        ImGui.begin(
            "##markerTip$uniqueId",
            null,
            ImGuiWindowFlags.NO_TITLE_BAR or ImGuiWindowFlags.NO_MOVE or
                ImGuiWindowFlags.NO_FOCUS_ON_APPEARING or ImGuiWindowFlags.NO_NAV_FOCUS,
        )
        ImGui.textWrapped(text)
        val pos = ImGui.getWindowPos()
        val size = ImGui.getWindowSize()
        markerTipMin = pos
        markerTipMax = ImVec2(pos.x + size.x, pos.y + size.y)
        ImGui.end()
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
            val maxX = (contentWidth - minimapWidthPx - viewWidth).coerceAtLeast(0f)
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
        // The keyboard may belong to a text field instead of the document:
        // either the editor's own find bar, or one of the host's fields (it
        // declares that through [keyboardOwnedByHost]). Guessing from ImGui's
        // io state instead disabled the editor's own keys entirely.
        if (keyboardOwnedByHost || (findVisible && findSearchFocused)) return
        val shift = ImGui.isKeyDown(ImGuiKey.MOD_SHIFT)
        val ctrl = ImGui.isKeyDown(ImGuiKey.MOD_CTRL)
        val alt = ImGui.isKeyDown(ImGuiKey.MOD_ALT)

        // Cmd/Ctrl+F opens find, Cmd/Ctrl+R also opens the replace row. The
        // primary modifier is Cmd on macOS (ImGui's Ctrl/Super naming is not
        // reliable across backends, so both are accepted).
        val primary = ctrl || ImGui.isKeyDown(ImGuiKey.MOD_SUPER)
        if (primary && ImGui.isKeyPressed(ImGuiKey.F, false)) {
            openFind(withReplace = false)
            return
        }
        if (primary && ImGui.isKeyPressed(ImGuiKey.R, false)) {
            openFind(withReplace = true)
            return
        }
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
        suppressHover()
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
        suppressHover()
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
        foldPreviewHover = false
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
                    ImVec2(origin.x + breakpointColumnWidth + 4f, numY),
                    "${line + 1}",
                    color.toImGuiColor(),
                )
                // Breakpoint marker in its own column: an IntelliJ icon per
                // state (plain / verified / rejected / disabled / logpoint),
                // with the condition badge overlaid when it has one.
                breakpoints[line + 1]?.let { breakpoint ->
                    val size = lineHeight
                    val bx = origin.x + (breakpointColumnWidth - size) / 2f
                    val by = numY + (lineHeight - size) / 2f
                    BreakpointIcons.iconFor(breakpoint).draw(ImVec2(bx, by), size)
                    if (breakpoint.conditional) {
                        val badge = size * 0.62f
                        BreakpointIcons.conditionalBadge.draw(
                            ImVec2(bx + size - badge, by + size - badge),
                            badge,
                        )
                    }
                }
                // Fold marker at the right edge of the gutter: a boxed "-"
                // when expanded (click to fold) and "+" when collapsed (click
                // to unfold) — IntelliJ style frame so the toggle targets are
                // visible at a glance. ASCII glyphs render in any mono font.
                if (foldAt(line) != null) {
                    val folded = line in collapsedStarts
                    val markerX = origin.x + gutterWidth - charWidth - foldMarkerGap()
                    val color = palette[PaletteIndex.LINE_NUMBER].toImGuiColor()
                    // Square frame centred on the glyph (not a stretched
                    // rectangle): side = line height minus a small margin.
                    val side = lineHeight - 4f
                    val cx = markerX + charWidth / 2f
                    val cy = numY + lineHeight / 2f
                    drawList.DrawRect(
                        ImVec2(cx - side / 2f, cy - side / 2f),
                        ImVec2(cx + side / 2f, cy + side / 2f),
                        color,
                        2f,
                        0,
                        1f,
                    )
                    drawList.DrawText(
                        ImVec2(markerX, numY),
                        if (folded) "+" else "-",
                        color,
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

            // Host + find highlights sit between the line background and the
            // glyphs.
            val hostMarks = highlightsByLine[line]
            val findMarks = findHighlightByLine[line]
            val renameMarks = renameHighlightByLine[line]
            if (hostMarks != null || findMarks != null || renameMarks != null) {
                val marks = ArrayList<EditorHighlight>(
                    (hostMarks?.size ?: 0) + (findMarks?.size ?: 0) + (renameMarks?.size ?: 0),
                )
                hostMarks?.let { marks.addAll(it) }
                findMarks?.let { marks.addAll(it) }
                renameMarks?.let { marks.addAll(it) }
                val lineX = textStartX - scrollX
                for (h in marks) {
                    val from = h.start.coerceIn(0, text.length)
                    val to = h.end.coerceIn(from, text.length)
                    if (to <= from) continue
                    val x0 = lineX + lineAdvance(line, from)
                    val x1 = lineX + lineAdvance(line, to)
                    drawList.DrawRectFilled(
                        ImVec2(x0, y),
                        ImVec2(x1, y + lineHeight),
                        h.fill.toImGuiColor(),
                    )
                    h.border?.let { border ->
                        drawList.DrawRect(
                            ImVec2(x0, y),
                            ImVec2(x1, y + lineHeight),
                            border.toImGuiColor(),
                            0f,
                            0,
                            h.borderThickness,
                        )
                    }
                }
            }

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
            // Ellipsis when the line is a collapsed fold start: hovering it
            // previews the hidden snippet, clicking it unfolds (handled in
            // handleMouse so the caret does not also jump).
            if (line in collapsedStarts) {
                val ellipsis = "..."
                val ex = x + charWidth * 0.5f
                val ew = ImGui.calcTextSize(ellipsis).x
                val hovered = ImGui.isWindowHovered() &&
                    ImGui.getMousePos().let { m ->
                        m.x >= ex && m.x <= ex + ew && m.y >= y && m.y <= y + lineHeight
                    }
                drawList.DrawText(
                    ImVec2(ex, y),
                    ellipsis,
                    if (hovered) {
                        palette[PaletteIndex.TEXT].toImGuiColor()
                    } else {
                        palette[PaletteIndex.INLAY_HINT].toImGuiColor()
                    },
                )
                if (hovered) requestFoldPreview(line)
            }
            // Diagnostic squiggle under the reported character range(s)
            // (falls back to the whole line when no range is given).
            val marker = markers[line]
            val underline = marker?.underlineColor
            if (underline != null && text.isNotEmpty()) {
                val lineX = textStartX - scrollX
                val ranges = marker.underlineRanges
                val waveY = y + lineHeight - 2.5f
                if (ranges.isNullOrEmpty()) {
                    drawSquiggle(
                        drawList,
                        lineX,
                        lineX + lineAdvance(line, text.length),
                        waveY,
                        underline.toImGuiColor(),
                    )
                } else {
                    for (r in ranges) {
                        val from = r.first.coerceIn(0, text.length)
                        val to = r.second.coerceIn(from, text.length)
                        if (to > from) {
                            drawSquiggle(
                                drawList,
                                lineX + lineAdvance(line, from),
                                lineX + lineAdvance(line, to),
                                waveY,
                                underline.toImGuiColor(),
                            )
                        }
                    }
                }
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
            // Clip to the text area like everything else: a caret past the
            // viewport's right edge would otherwise paint into the minimap
            // strip (it sits right after the text region).
            ImGui.pushClipRect(
                ImVec2(textStartX, origin.y),
                ImVec2(origin.x + width, origin.y + height),
                true,
            )
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
            ImGui.popClipRect()
        }

        // ==================== Minimap ====================
        // A fixed-size scaled overview of the whole document (colortextedit
        // style): each visible line gets a fixed row height, token colors
        // are drawn as per-column blocks, and a translucent viewport window
        // tracks the visible range. The strip scrolls independently of the
        // text. Clicking/dragging scrolls (see handleMouse).
        if (showMinimap && minimapWidthPx > 0f && minimapRowHeight > 0f) {
            val mmX = origin.x + width
            val mmY = origin.y
            val mmW = minimapWidthPx
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
                if (spans.isEmpty()) {
                    // No syntax/semantic spans (plain text, or a language the
                    // host does not highlight): still draw the line's shape in
                    // a muted text colour, so the minimap shows the document
                    // instead of going blank.
                    val first = text.indexOfFirst { !it.isWhitespace() }
                    if (first >= 0) {
                        val x0 = mmX + first * colW
                        val x1 = (mmX + text.length * colW).coerceAtMost(mmX + mmW)
                        if (x1 > x0) {
                            drawList.DrawRectFilled(
                                ImVec2(x0, y),
                                ImVec2(x1, y + colH),
                                palette[PaletteIndex.TEXT].toImGuiColor(),
                            )
                        }
                    }
                    continue
                }
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
     * Draws a small sine-wave squiggle from [x0] to [x1] centered on [y]
     * (used for diagnostic underlines). One short segment per pixel keeps
     * the wave smooth; callers keep the span to the visible text.
     */
    private fun drawSquiggle(drawList: ImDrawList, x0: Float, x1: Float, y: Float, color: Int) {
        if (x1 <= x0) return
        val amplitude = 1.6f
        val period = 5f
        var x = x0
        var prevY = y - amplitude
        while (x <= x1) {
            val phase = (x - x0) / period * 2f * PI.toFloat()
            val waveY = y - amplitude * sin(phase)
            drawList.DrawLine(ImVec2((x - 1f).coerceAtLeast(x0), prevY), ImVec2(x, waveY), color, 1f)
            prevY = waveY
            x += 1f
        }
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
    /** Screen-space x of column [index] on [line] (for host-drawn overlays). */
    fun posScreenX(line: Int, index: Int): Float = textStartX - scrollX + lineVisualAdvance(line, index)

    /** Screen-space y of [line]'s top edge (null when the line is not visible). */
    fun posScreenY(line: Int): Float? {
        val row = line.visibleRowOrNull() ?: return null
        return textStartY + row * lineHeight - (scrollY % lineHeight)
    }

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
    /** When set, a squiggly underline in this color is drawn under the line. */
    val underlineColor: Color? = null,

    /**
     * Character ranges (start inclusive, end exclusive) of the underline.
     * Null/empty draws the wave under the whole line's text.
     */
    val underlineRanges: List<Pair<Int, Int>>? = null,
    val lineNumberTooltip: String? = null,
    val textTooltip: String? = null,
)

/**
 * A background highlight: the character range [start, end) of [line] gets a
 * translucent fill (and an optional border) drawn under the text, VSCode
 * style. Rendered by the editor together with the text, so it scrolls,
 * clips to the text area and stays aligned exactly like the glyphs.
 */
data class EditorHighlight(
    val line: Int,
    val start: Int,
    val end: Int,
    /** Packed 0xRRGGBBAA fill colour (translucency comes from the alpha). */
    val fill: Color,
    /** Optional packed border colour. */
    val border: Color? = null,
    val borderThickness: Float = 1f,
)

private fun isWord(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
