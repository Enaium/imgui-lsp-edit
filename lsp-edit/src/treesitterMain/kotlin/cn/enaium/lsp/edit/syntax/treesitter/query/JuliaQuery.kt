// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Julia (from zed highlights.scm). */
object JuliaQuery {
    val query: String = """
|(block_comment) @comment
|(line_comment) @comment
|(prefixed_string_literal) @string
|(string_interpolation) @string
|(string_literal) @string
|(float_literal) @number
|(integer_literal) @number
|["abstract" "as" "baremodule" "begin" "catch" "const" "do" "else" "elseif" "end" "export" "false" "finally" "for" "function" "global" "if" "import" "let" "local" "macro" "module" "mutable" "outer" "primitive" "public" "quote" "return" "struct" "true" "try" "type" "using" "where" "while"] @keyword
|(function_definition (signature (identifier) @function))
|(struct_definition (type_head (identifier) @type))
""".trimMargin()
}
