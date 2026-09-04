// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Embedded Template (from zed highlights.scm). */
object EmbeddedTemplateQuery {
    val query: String = """
|(comment) @comment
|(comment_directive) @comment
|(comment) @comment
|(comment_directive) @comment
|(template) @string
""".trimMargin()
}
