package cn.enaium.lsp.edit.diff

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2

/**
 * Side-by-side file diff renderer.
 *
 * Renders two read-only panes — the old text on the left, the new text on
 * the right — with line-level differences computed by [Diff]. Removed lines
 * get a red tint, added lines a green tint, and the two panes share a
 * single vertical scrollbar so matching lines stay aligned.
 *
 * Call [render] inside any ImGui window.
 */
class DiffView(
    private var oldText: String = "",
    private var newText: String = "",
) {

    /** Stable suffix for widget IDs; callers may set it to avoid collisions. */
    var uniqueId: Long = 0L

    /** 0xRRGGBBAA colors for the diff panes. */
    var removedBg: Int = 0xFF5C2E2E.toInt()
    var addedBg: Int = 0xFF2E5C35.toInt()
    var removedLineNumber: Int = 0xFFE07A7A.toInt()
    var addedLineNumber: Int = 0xFF7AE09A.toInt()
    var lineNumber: Int = 0xFF808080.toInt()
    var textColor: Int = 0xFFD0D0D0.toInt()
    var background: Int = 0xFF1E1E1E.toInt()
    var separator: Int = 0xFF3A3A3A.toInt()

    fun setTexts(old: String, new: String) {
        oldText = old
        newText = new
    }

    /**
     * Converts a 0xRRGGBBAA color (the [DiffView] field convention) into the
     * 0xAABBGGRR order ImGui's ImU32 expects. Without the swap, the red and
     * blue channels trade places: the deletion tint renders blue/purple
     * instead of the intended red.
     */
    private fun Int.toImGuiColor(): Int {
        val a = (this ushr 24) and 0xFF
        val r = (this ushr 16) and 0xFF
        val g = (this ushr 8) and 0xFF
        val b = this and 0xFF
        return (a shl 24) or (b shl 16) or (g shl 8) or r
    }

    /** Renders the diff; returns true while the diff view has focus. */
    fun render(
        title: String,
        size: ImVec2 = ImVec2(-1f, -1f),
        childFlags: Int = 0,
        windowFlags: Int = 0,
    ): Boolean {
        val id = if (title.isEmpty()) "##diff$uniqueId" else title
        ImGui.beginChild(id, size, childFlags, windowFlags or ImGuiWindowFlags.HORIZONTAL_SCROLLBAR)

        val lineHeight = ImGui.getTextLineHeight().coerceAtLeast(1f)
        val charWidth = ImGui.calcTextSize("M").x.coerceAtLeast(1f)
        val avail = ImGui.getContentRegionAvail()
        val cursorPos = ImGui.getCursorScreenPos()
        val scrollX = ImGui.getScrollX()
        val scrollY = ImGui.getScrollY()
        // The cursor's screen position is scroll-subtracted (it points at
        // the content origin, which moves with the scroll). Add the scroll
        // back so origin describes the FIXED viewport; the drawing code then
        // applies the scroll exactly once. Without this, scrolled frames
        // rendered everything off-screen (double-subtracting the scroll).
        val originX = cursorPos.x + scrollX
        val originY = cursorPos.y + scrollY

        val lines = Diff.compute(oldText, newText)

        // Two equal-width panes with a 1px separator and a gutter each.
        val gutterW = charWidth * 4 + 8f // "1234" plus padding
        val paneW = ((avail.x - gutterW * 2 - 1f) / 2f).coerceAtLeast(1f)
        val contentHeight = (lines.size * lineHeight).coerceAtLeast(avail.y)

        // Background: fixed to the viewport (not the scrolled content).
        val drawList = ImGui.getWindowDrawList()
        drawList.DrawRectFilled(
            ImVec2(originX, originY),
            ImVec2(originX + avail.x, originY + avail.y),
            background.toImGuiColor(),
        )

        val firstRow = (scrollY / lineHeight).toInt().coerceAtLeast(0)
        val lastRow = ((scrollY + avail.y) / lineHeight).toInt().coerceAtMost(lines.size - 1)

        for (row in firstRow..lastRow) {
            val line = lines[row]
            val y = originY + row * lineHeight - scrollY
            val leftX = originX - scrollX
            val rightX = leftX + gutterW + paneW + 1f

            val isRemoved = line.kind == DiffKind.REMOVED
            val isAdded = line.kind == DiffKind.ADDED

            // Left pane background.
            if (isRemoved) {
                drawList.DrawRectFilled(
                    ImVec2(leftX, y),
                    ImVec2(leftX + gutterW + paneW, y + lineHeight),
                    removedBg.toImGuiColor(),
                )
            }
            // Right pane background.
            if (isAdded) {
                drawList.DrawRectFilled(
                    ImVec2(rightX, y),
                    ImVec2(rightX + gutterW + paneW, y + lineHeight),
                    addedBg.toImGuiColor(),
                )
            }

            // Left gutter: old line number (only when the line exists on the left).
            if (line.oldLine != null) {
                drawList.DrawText(
                    ImVec2(leftX + 4f, y),
                    "${line.oldLine}",
                    if (isRemoved) removedLineNumber.toImGuiColor() else lineNumber.toImGuiColor(),
                )
            }
            // Left text.
            if (line.kind != DiffKind.ADDED) {
                drawList.DrawText(
                    ImVec2(leftX + gutterW + 4f, y),
                    line.text,
                    textColor.toImGuiColor(),
                )
            }

            // Right gutter: new line number (only when the line exists on the right).
            if (line.newLine != null) {
                drawList.DrawText(
                    ImVec2(rightX + 4f, y),
                    "${line.newLine}",
                    if (isAdded) addedLineNumber.toImGuiColor() else lineNumber.toImGuiColor(),
                )
            }
            // Right text.
            if (line.kind != DiffKind.REMOVED) {
                drawList.DrawText(
                    ImVec2(rightX + gutterW + 4f, y),
                    line.text,
                    textColor.toImGuiColor(),
                )
            }
        }

        // Center separator between the two panes.
        val sepX = originX - scrollX + gutterW + paneW + 0.5f
        drawList.DrawLine(
            ImVec2(sepX, originY),
            ImVec2(sepX, originY + avail.y),
            separator.toImGuiColor(),
            1f,
        )

        // Capture clicks so the parent window does not get dragged, and
        // extend the scrollable region.
        ImGui.invisibleButton("##diffCapture$uniqueId", ImVec2(avail.x, contentHeight))
        ImGui.setCursorPos(ImVec2(avail.x, contentHeight))
        ImGui.dummy(ImVec2(1f, 1f))

        val focused = ImGui.isWindowFocused() || ImGui.isWindowHovered()
        ImGui.endChild()
        return focused
    }
}
