package cn.enaium.lsp.edit.lsp

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImVec2
import cn.enaium.lsp.model.CompletionItemKind
import cn.enaium.xicons.imgui.Icon
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesClassDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesConstantDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesConstructorDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesEnumDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesFieldDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesFolderDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesFunctionDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesInterfaceDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesMethodDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesModuleDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesParameterDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesPropertyDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiNodesVariableDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiFileTypesAnyTypeDark

/**
 * IntelliJ node icons for LSP completion kinds.
 *
 * Uses the `Expui*Dark` set (IntelliJ's new UI, dark variant) since the
 * editor chrome is dark. Kinds IntelliJ has no distinct icon for fall back
 * to the closest structural relative; ones with no sensible relative at all
 * (keyword, snippet, color, operator, …) return null and render unadorned.
 */
internal object CompletionIcons {

    /** Space between the icon and the text it labels. */
    private const val GAP = 4f

    /**
     * Edge length of the icons for the active font. Tied to the text line
     * height so the icon keeps the same visual weight as the labels when the
     * host scales its font.
     */
    fun size(): Float = ImGui.getTextLineHeight()

    /** Horizontal space an item reserves for its icon plus [GAP]. */
    fun gutter(): Float = size() + GAP

    /**
     * Draws [icon] centered on the line starting at [min]: vertically against
     * the line height (not the item, which may span several wrapped lines) and
     * [GAP]/2 in from the left edge of the row.
     */
    fun drawCentered(icon: Icon, min: ImVec2) {
        val edge = size()
        icon.draw(ImVec2(min.x + GAP * 0.5f, min.y + (size() - edge) / 2f), edge)
    }

    /** Icon for [kind] (`CompletionItemKind.*`), or null when there is none. */
    fun iconFor(kind: Int?): Icon? = when (kind) {
        CompletionItemKind.Method -> ExpuiNodesMethodDark
        CompletionItemKind.Function -> ExpuiNodesFunctionDark
        CompletionItemKind.Constructor -> ExpuiNodesConstructorDark
        CompletionItemKind.Field -> ExpuiNodesFieldDark
        CompletionItemKind.Variable -> ExpuiNodesVariableDark
        CompletionItemKind.Class -> ExpuiNodesClassDark
        CompletionItemKind.Interface -> ExpuiNodesInterfaceDark
        CompletionItemKind.Module -> ExpuiNodesModuleDark
        CompletionItemKind.Property -> ExpuiNodesPropertyDark
        CompletionItemKind.Enum -> ExpuiNodesEnumDark
        CompletionItemKind.Constant -> ExpuiNodesConstantDark
        CompletionItemKind.Folder -> ExpuiNodesFolderDark
        // No dedicated icon in the IntelliJ set: pick the nearest structure.
        CompletionItemKind.Value -> ExpuiNodesConstantDark
        CompletionItemKind.TypeParameter -> ExpuiNodesParameterDark
        CompletionItemKind.EnumMember -> ExpuiNodesEnumDark
        CompletionItemKind.Struct -> ExpuiNodesClassDark
        CompletionItemKind.File -> ExpuiFileTypesAnyTypeDark
        else -> null
    }
}
