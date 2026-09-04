// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Scala (from zed highlights.scm). */
object ScalaQuery {
    val query: String = """
|(block_comment) @comment
|(comment) @comment
|(interpolated_string) @string
|(floating_point_literal) @number
|(integer_literal) @number
|["_" "abstract" "as" "case" "catch" "class" "def" "derives" "do" "else" "end" "enum" "export" "extends" "extension" "false" "final" "finally" "for" "given" "if" "implicit" "import" "infix" "inline" "lazy" "macro" "match" "new" "object" "opaque" "open" "override" "package" "private" "protected" "return" "sealed" "then" "this" "throw" "trait" "transparent" "true" "try" "type" "using" "val" "var" "while" "with" "yield"] @keyword
|(function_definition name: (identifier) @function)
|(class_definition name: (identifier) @type)
""".trimMargin()
}
