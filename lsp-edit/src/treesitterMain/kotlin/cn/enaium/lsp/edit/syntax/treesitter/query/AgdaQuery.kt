// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Agda (from zed highlights.scm). */
object AgdaQuery {
    val query: String = """
|(comment) @comment
|(integer) @number
|["CATCHALL" "Prop" "Set" "_" "abstract" "bid" "codata" "coinductive" "constructor" "data" "data_name" "do" "field" "forall" "hiding" "import" "in" "inductive" "infix" "infixl" "infixr" "instance" "let" "macro" "module" "mutual" "open" "overlap" "pattern" "postulate" "primitive" "private" "public" "quote" "quoteContext" "quoteGoal" "quoteTerm" "record" "renaming" "rewrite" "syntax" "tactic" "to" "unquote" "unquoteDecl" "unquoteDef" "using" "variable" "where" "with"] @keyword
""".trimMargin()
}
