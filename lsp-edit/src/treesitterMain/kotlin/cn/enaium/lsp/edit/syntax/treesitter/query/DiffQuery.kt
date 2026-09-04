// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Diff (from zed highlights.scm). */
object DiffQuery {
    val query: String = """
|(comment) @comment
|
|[
|  (addition)
|  (new_file)
|] @string @diff.plus
|
|[
|  (deletion)
|  (old_file)
|] @keyword @diff.minus
|
|(commit) @constant
|
|(location) @attribute
|
|(command
|  "diff" @function
|  (argument) @variable.parameter)
|
|(mode) @number
|
|[
|  ".."
|  "+"
|  "++"
|  "+++"
|  "++++"
|  "-"
|  "--"
|  "---"
|  "----"
|] @punctuation.special
|
|[
|  (binary_change)
|  (similarity)
|  (file_change)
|] @label
|
|(index
|  "index" @keyword)
|
|(similarity
|  (score) @number
|  "%" @number)
""".trimMargin()
}
