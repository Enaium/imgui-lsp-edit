package cn.enaium.lsp.edit.example

import cn.enaium.lsp.edit.syntax.treesitter.TreeSitterHighlighter
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Loads every bundled tree-sitter grammar, runs its highlight query against
 * the sample source, and asserts that highlighting produced spans. Guards
 * against grammar/query mismatches (bad node types, capture names, native
 * library loading).
 */
class AllLanguagesHighlightTest {

    @Test
    fun everyGrammarHighlightsSample() {
        val specs = TreeSitterLanguageSpec.entries
        assertTrue(specs.size >= 30, "expected all grammars, got ${specs.size}")
        for (spec in specs) {
            val sample = spec.sample
            assertTrue(sample.isNotBlank(), "${spec.displayName} has no sample")
            val language = try {
                spec.language()
            } catch (e: Throwable) {
                throw AssertionError("${spec.displayName}: grammar load failed: ${e.message}", e)
            }
            assertNotNull(language, "${spec.displayName}: null grammar")

            val highlighter = try {
                TreeSitterHighlighter(language, spec.query)
            } catch (e: Throwable) {
                throw AssertionError("${spec.displayName}: highlighter init failed: ${e.message}", e)
            }
            highlighter.setText(sample)

            val lineCount = sample.lines().size
            var totalSpans = 0
            for (line in 0 until lineCount) {
                totalSpans += highlighter.spansForLine(line)?.size ?: 0
            }
            assertTrue(
                totalSpans > 0,
                "${spec.displayName}: query produced no spans (query may not match the sample)",
            )
        }
    }
}
