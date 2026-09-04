// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for TOML (from zed highlights.scm). */
object TomlQuery {
    val query: String = """
|(bare_key) @property
|
|(quoted_key) @property
|
|(boolean) @constant
|
|(comment) @comment
|
|(integer) @number
|
|(float) @number
|
|(string) @string
|
|(escape_sequence) @string.escape
|
|(offset_date_time) @string.special
|
|(local_date_time) @string.special
|
|(local_date) @string.special
|
|(local_time) @string.special
|
|[
|  "."
|  ","
|] @punctuation.delimiter
|
|"=" @operator
|
|[
|  "["
|  "]"
|  "[["
|  "]]"
|  "{"
|  "}"
|] @punctuation.bracket
""".trimMargin()
}
