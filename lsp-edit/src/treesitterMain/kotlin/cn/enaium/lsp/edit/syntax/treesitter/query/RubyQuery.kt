// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Ruby (from zed highlights.scm). */
object RubyQuery {
    val query: String = """
|[
|  (identifier)
|  (global_variable)
|] @variable
|
|[
|  "class"
|  "def"
|  "module"
|] @keyword.function
|
|[
|  "case"
|  "else"
|  "elsif"
|  "if"
|  "then"
|  "unless"
|  "when"
|] @keyword.control.conditional
|
|[
|  "do"
|  "for"
|  "until"
|  "while"
|] @keyword.control.repeat
|
|[
|  "break"
|  "next"
|  "retry"
|  "return"
|  "yield"
|] @keyword.control.return
|
|[
|  "begin"
|  "ensure"
|  "rescue"
|] @keyword.exception
|
|[
|  "alias"
|  "and"
|  "end"
|  "in"
|  "or"
|] @keyword
|
|((identifier) @keyword
|  (#match? @keyword "^(private|protected|public)${'$'}"))
|
|(call
|  method: [
|    (identifier)
|    (constant)
|  ] @function.method)
|
|((identifier) @keyword.import
|  (#any-of? @keyword.import "require" "require_relative" "load"))
|
|"defined?" @function.method.builtin
|
|(alias
|  (identifier) @function.method)
|
|(setter
|  (identifier) @function.method)
|
|(method
|  name: [
|    (identifier)
|    (constant)
|  ] @function.method.definition)
|
|(singleton_method
|  name: [
|    (identifier)
|    (constant)
|  ] @function.method.definition)
|
|(method_parameters
|  [
|    (identifier) @variable.parameter
|    (optional_parameter
|      name: (identifier) @variable.parameter)
|    (keyword_parameter
|      [
|        name: (identifier)
|        ":"
|      ] @variable.parameter.keyword)
|  ])
|
|(block_parameters
|  (identifier) @variable.parameter)
|
|((call
|  method: (identifier) @_locals
|  arguments: (argument_list
|    (parenthesized_statements
|      (call
|        method: (identifier) @variable.parameter.keyword))))
|  (#eq? @_locals "locals"))
|
|((call
|  method: (identifier) @_locals
|  arguments: (argument_list
|    (parenthesized_statements
|      (call
|        arguments: (argument_list
|          (pair
|            key: (hash_key_symbol) @variable.parameter.keyword))))))
|  (#eq? @_locals "locals")
|  (#not-eq? @variable.parameter.keyword ""))
|
|((identifier) @constant.builtin
|  (#match? @constant.builtin "^__(FILE|LINE|ENCODING)__${'$'}"))
|
|(file) @constant.builtin
|
|(line) @constant.builtin
|
|(encoding) @constant.builtin
|
|(hash_splat_nil
|  "**" @operator) @constant.builtin
|
|(constant) @type
|
|((constant) @constant
|  (#match? @constant "^[A-Z\\d_]+${'$'}"))
|
|(superclass
|  (constant) @type.super)
|
|(superclass
|  (scope_resolution
|    (constant) @type.super))
|
|(superclass
|  (scope_resolution
|    (scope_resolution
|      (constant) @type.super)))
|
|(self) @variable.special
|
|(super) @variable.special
|
|(class_variable) @variable.special
|
|(instance_variable) @variable.special.instance
|
|((call
|  !receiver
|  method: (identifier) @function.builtin)
|  (#any-of? @function.builtin "include" "extend" "prepend" "refine" "using"))
|
|((identifier) @keyword.exception
|  (#any-of? @keyword.exception "raise" "fail" "catch" "throw"))
|
|[
|  (string)
|  (bare_string)
|  (subshell)
|  (heredoc_body)
|  (heredoc_beginning)
|] @string
|
|[
|  (simple_symbol)
|  (delimited_symbol)
|  (hash_key_symbol)
|  (bare_symbol)
|] @string.special.symbol
|
|(regex) @string.regex
|
|(escape_sequence) @string.escape
|
|[
|  (integer)
|  (float)
|] @number
|
|[
|  (true)
|  (false)
|] @boolean
|
|(nil) @constant.builtin
|
|((comment) @comment
|  (#not-match? @comment "^\\s*#[:|]")
|  (#not-match? @comment "^\\s*#\\s*(@rbs|\\|)"))
|
|[
|  "!"
|  "~"
|  "+"
|  "-"
|  "**"
|  "*"
|  "/"
|  "%"
|  "<<"
|  ">>"
|  "&"
|  "|"
|  "^"
|  ">"
|  "<"
|  "<="
|  ">="
|  "=="
|  "!="
|  "=~"
|  "!~"
|  "<=>"
|  "||"
|  "&&"
|  ".."
|  "..."
|  "="
|  "**="
|  "*="
|  "/="
|  "%="
|  "+="
|  "-="
|  "<<="
|  ">>="
|  "&&="
|  "&="
|  "||="
|  "|="
|  "^="
|  "=>"
|  "->"
|  (operator)
|] @operator
|
|[
|  ","
|  ";"
|  "."
|  "::"
|] @punctuation.delimiter
|
|[
|  "("
|  ")"
|  "["
|  "]"
|  "{"
|  "}"
|  "%w("
|  "%i("
|] @punctuation.bracket
|
|(interpolation
|  "#{" @punctuation.special
|  "}" @punctuation.special) @embedded
""".trimMargin()
}
