// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for HTML (from zed highlights.scm). */
object HtmlQuery {
    val query: String = """
|(comment) @comment
|(quoted_attribute_value) @string
|["doctype"] @keyword
|(tag_name) @keyword
|(attribute_name) @property
|(quoted_attribute_value) @string
""".trimMargin()
}
