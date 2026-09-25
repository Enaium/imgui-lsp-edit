package cn.enaium.lsp.edit.lsp

import cn.enaium.lsp.model.DocumentSymbol
import cn.enaium.lsp.model.Range
import cn.enaium.lsp.model.SymbolInformation

/**
 * Shapes the flat `SymbolInformation` form of `textDocument/documentSymbol`
 * into the tree the hierarchical `DocumentSymbol` form describes.
 *
 * A flat server (pylsp, for one) reports every symbol as a sibling — the
 * container is only a name in `containerName` — so a document renders as one
 * long list instead of a structure. Each symbol is attached to the closest
 * preceding symbol that contains it: the one `containerName` names when the
 * server set it, otherwise the innermost range enclosing it. A symbol that
 * ends up nested no longer repeats its container in `detail`; the few whose
 * container the server did not report keep it, so the qualifier is not lost.
 *
 * A server that reports one entry per assignment (pylsp does for a variable
 * assigned twice) would otherwise repeat the same row under its parent; the
 * first entry wins, keeping the outline readable.
 */
internal fun buildSymbolTree(flat: List<SymbolInformation>): List<DocumentSymbol> {
    val nodes = flat.map { Node(it) }
    val roots = mutableListOf<Node>()
    for ((index, node) in nodes.withIndex()) {
        val container = node.symbol.containerName
        val parent = nodes.subList(0, index)
            .filter { contains(it.symbol.location.range, node.symbol.location.range) }
            .filter { container == null || it.symbol.name == container }
            .lastOrNull()
        val siblings = parent?.children ?: roots
        // Same variable, same kind, same parent: one row is enough.
        if (siblings.any { it.symbol.name == node.symbol.name && it.symbol.kind == node.symbol.kind }) continue
        node.nested = parent != null
        siblings += node
    }
    return roots.map(::toSymbol)
}

private class Node(val symbol: SymbolInformation) {
    /** Set when the symbol was attached to a container. */
    var nested = false
    val children = mutableListOf<Node>()
}

private fun toSymbol(node: Node): DocumentSymbol = DocumentSymbol(
    name = node.symbol.name,
    kind = node.symbol.kind,
    range = node.symbol.location.range,
    selectionRange = node.symbol.location.range,
    detail = if (node.nested) null else node.symbol.containerName,
    tags = node.symbol.tags,
    deprecated = node.symbol.deprecated,
    children = node.children.map(::toSymbol).ifEmpty { null },
)

/** True when [outer] strictly encloses [inner]. */
private fun contains(outer: Range, inner: Range): Boolean {
    val same = outer.start == inner.start && outer.end == inner.end
    if (same) return false
    val startsBefore = outer.start.line < inner.start.line ||
        (outer.start.line == inner.start.line && outer.start.character <= inner.start.character)
    val endsAfter = outer.end.line > inner.end.line ||
        (outer.end.line == inner.end.line && outer.end.character >= inner.end.character)
    return startsBefore && endsAfter
}
