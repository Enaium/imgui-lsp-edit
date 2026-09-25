package cn.enaium.lsp.edit

import cn.enaium.xicons.imgui.Icon
import cn.enaium.xicons.imgui.icons.intellij.ExpuiBreakpointsBreakpointDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiBreakpointsBreakpointDisabledDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiBreakpointsBreakpointInvalidDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiBreakpointsBreakpointUnsuspendentDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiBreakpointsBreakpointValidDark
import cn.enaium.xicons.imgui.icons.intellij.ExpuiBreakpointsQuestionBadgeDark

/**
 * The IntelliJ breakpoint icons the gutter draws, one per state.
 *
 * The `Dark` variants are the ones IntelliJ's new UI uses on a dark theme,
 * which is what the editor's palettes are: the marker keeps its colour there
 * (breakpoints stay red), only the surrounding chrome differs.
 */
internal object BreakpointIcons {

    /** An enabled breakpoint no adapter has judged yet. */
    val enabled: Icon = ExpuiBreakpointsBreakpointDark

    /** An enabled breakpoint the adapter verified. */
    val verified: Icon = ExpuiBreakpointsBreakpointValidDark

    /** An enabled breakpoint the adapter rejected (e.g. no code on that line). */
    val invalid: Icon = ExpuiBreakpointsBreakpointInvalidDark

    /** A breakpoint the user turned off: kept, but not sent to the adapter. */
    val disabled: Icon = ExpuiBreakpointsBreakpointDisabledDark

    /** A logpoint: logs a message instead of suspending. */
    val logpoint: Icon = ExpuiBreakpointsBreakpointUnsuspendentDark

    /** Overlaid on a breakpoint that carries a condition. */
    val conditionalBadge: Icon = ExpuiBreakpointsQuestionBadgeDark

    /** The icon for [breakpoint], ignoring the condition badge. */
    fun iconFor(breakpoint: EditorBreakpoint): Icon = when {
        !breakpoint.enabled -> disabled
        breakpoint.logpoint -> logpoint
        breakpoint.verified == true -> verified
        breakpoint.verified == false -> invalid
        else -> enabled
    }
}
