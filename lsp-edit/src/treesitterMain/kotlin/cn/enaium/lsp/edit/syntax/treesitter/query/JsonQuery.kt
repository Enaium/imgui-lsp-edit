// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for JSON (from zed highlights.scm). */
object JsonQuery {
    val query: String = """
|(comment) @comment
|
|(string) @string
|
|(escape_sequence) @string.escape
|
|(pair
|  key: (string) @property.json_key)
|
|(number) @number
|
|[
|  (true)
|  (false)
|] @boolean
|
|(null) @constant.builtin
|
|[
|  ","
|  ":"
|] @punctuation.delimiter
|
|[
|  "{"
|  "}"
|  "["
|  "]"
|] @punctuation.bracket
""".trimMargin()
}
