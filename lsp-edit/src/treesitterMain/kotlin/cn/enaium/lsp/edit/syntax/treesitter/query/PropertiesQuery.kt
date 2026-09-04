// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Properties (from zed highlights.scm). */
object PropertiesQuery {
    val query: String = """
|(comment) @comment
|(key) @property
|(value) @string
""".trimMargin()
}
