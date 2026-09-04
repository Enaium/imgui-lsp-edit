// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for YAML (from zed highlights.scm). */
object YamlQuery {
    val query: String = """
|(comment) @comment
|(double_quote_scalar) @string
|(single_quote_scalar) @string
|(block_scalar) @string
|(string_scalar) @string
|(plain_scalar) @string
|(integer_scalar) @number
|(float_scalar) @number
|(boolean_scalar) @keyword
|(null_scalar) @keyword
|(block_mapping_pair key: (flow_node (plain_scalar)) @property)
""".trimMargin()
}
