package cn.enaium.lsp.edit.lsp

import cn.enaium.lsp.model.DocumentSymbol
import cn.enaium.lsp.model.Location
import cn.enaium.lsp.model.Position
import cn.enaium.lsp.model.Range
import cn.enaium.lsp.model.SymbolInformation
import cn.enaium.lsp.model.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The structure pane gets a tree from either form of
 * `textDocument/documentSymbol`: a flat server's sibling list is nested by
 * the container each symbol names.
 */
class SymbolTreeTest {

    private fun info(
        name: String,
        kind: Int,
        startLine: Int,
        endLine: Int,
        container: String? = null,
        startCharacter: Int = 0,
        endCharacter: Int = 0,
    ) = SymbolInformation(
        name = name,
        kind = kind,
        location = Location(
            uri = "file:///main.py",
            range = Range(
                start = Position(line = startLine, character = startCharacter),
                end = Position(line = endLine, character = endCharacter),
            ),
        ),
        containerName = container,
    )

    private fun names(symbols: List<DocumentSymbol>) = symbols.map { it.name }

    @Test
    fun flatSymbolsAreNestedByTheirContainer() {
        // What pylsp answers for the Python demo: fib(n) and main() at the top
        // level, main's variables (total, i, value) as siblings carrying their
        // container name — total twice, once per assignment.
        val flat = listOf(
            info("fib", SymbolKind.Function, 3, 7),
            info("main", SymbolKind.Function, 9, 16),
            info("total", SymbolKind.Variable, 10, 10, container = "main", startCharacter = 4, endCharacter = 13),
            info("i", SymbolKind.Variable, 11, 15, container = "main", startCharacter = 4),
            info("value", SymbolKind.Variable, 12, 12, container = "main", startCharacter = 8, endCharacter = 22),
            info("total", SymbolKind.Variable, 13, 13, container = "main", startCharacter = 8, endCharacter = 22),
        )

        val tree = buildSymbolTree(flat)

        assertEquals(listOf("fib", "main"), names(tree))
        assertEquals(emptyList(), names(tree[0].children.orEmpty()), "pylsp reports no parameter symbols")
        assertEquals(listOf("total", "i", "value"), names(tree[1].children.orEmpty()))
        // Nested symbols drop the container qualifier; the parent shows it.
        assertNull(tree[1].children!!.first().detail)
        // Children keep the server's order and ranges.
        assertEquals(10, tree[1].children!!.first().range.start.line)
        assertEquals(12, tree[1].children!!.last().range.start.line)
    }

    @Test
    fun symbolsWithoutAContainerNameNestByRange() {
        val flat = listOf(
            info("outer", SymbolKind.Function, 0, 20),
            info("inner", SymbolKind.Function, 4, 8),
            info("field", SymbolKind.Field, 5, 5, startCharacter = 4),
        )

        val tree = buildSymbolTree(flat)

        assertEquals(listOf("outer"), names(tree))
        assertEquals(listOf("inner"), names(tree[0].children.orEmpty()))
        assertEquals(listOf("field"), names(tree[0].children!!.first().children.orEmpty()))
    }

    @Test
    fun aContainerTheServerDidNotReportKeepsItsQualifier() {
        val flat = listOf(
            info("main", SymbolKind.Function, 0, 10),
            info("orphan", SymbolKind.Variable, 2, 2, container = "elsewhere"),
        )

        val tree = buildSymbolTree(flat)

        assertEquals(listOf("main", "orphan"), names(tree))
        assertEquals("elsewhere", tree[1].detail, "the qualifier survives when it cannot be nested")
    }
}
