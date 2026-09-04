// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for OCaml (from zed highlights.scm). */
object OcamlQuery {
    val query: String = """
|[
|  (module_name)
|  (module_type_name)
|] @title
|
|[
|  (class_name)
|  (class_type_name)
|  (type_constructor)
|] @type
|
|(tag) @variant ; Polymorphic Variants
|
|(constructor_name) @constructor ; Exceptions, variants and the like
|
|(let_binding
|  pattern: (value_name) @function
|  (parameter))
|
|(let_binding
|  pattern: (value_name) @function
|  body: [
|    (fun_expression)
|    (function_expression)
|  ])
|
|(value_specification
|  (value_name) @function)
|
|(external
|  (value_name) @function)
|
|(method_name) @function
|
|(infix_expression
|  left: (value_path
|    (value_name) @function)
|  operator: (concat_operator) @operator
|  (#eq? @operator "@@"))
|
|(infix_expression
|  operator: (rel_operator) @operator
|  right: (value_path
|    (value_name) @function)
|  (#eq? @operator "|>"))
|
|(application_expression
|  function: (value_path
|    (value_name) @function))
|
|(value_pattern) @variable
|
|(type_variable) @variable.special
|
|[
|  (field_name)
|  (instance_variable_name)
|] @property
|
|[
|  (label_name)
|  (parameter)
|] @label
|
|(parameter
|  pattern: (value_pattern) @label)
|
|(parameter
|  (label_name)
|  pattern: (value_pattern) @variable)
|
|(boolean) @boolean
|
|[
|  (number)
|  (signed_number)
|] @number
|
|[
|  (string)
|  (character)
|] @string
|
|(quoted_string
|  "{" @string
|  "}" @string) @string
|
|(quoted_string_content) @string
|
|(escape_sequence) @string.escape
|
|[
|  (conversion_specification)
|  (pretty_printing_indication)
|] @punctuation.special
|
|(match_expression
|  (match_operator) @keyword)
|
|(value_definition
|  [
|    (let_operator)
|    (let_and_operator)
|  ] @keyword)
|
|[
|  (prefix_operator)
|  (sign_operator)
|  (pow_operator)
|  (mult_operator)
|  (add_operator)
|  (concat_operator)
|  (rel_operator)
|  (and_operator)
|  (or_operator)
|  (assign_operator)
|  (hash_operator)
|  (indexing_operator)
|  (let_operator)
|  (let_and_operator)
|  (match_operator)
|] @operator
|
|[
|  "*"
|  "#"
|  "::"
|  "<-"
|] @operator
|
|[
|  "and"
|  "as"
|  "assert"
|  "begin"
|  "class"
|  "constraint"
|  "do"
|  "done"
|  "downto"
|  "else"
|  "end"
|  "exception"
|  "external"
|  "for"
|  "fun"
|  "function"
|  "functor"
|  "if"
|  "in"
|  "include"
|  "inherit"
|  "initializer"
|  "lazy"
|  "let"
|  "match"
|  "method"
|  "module"
|  "mutable"
|  "new"
|  "nonrec"
|  "object"
|  "of"
|  "open"
|  "private"
|  "rec"
|  "sig"
|  "struct"
|  "then"
|  "to"
|  "try"
|  "type"
|  "val"
|  "virtual"
|  "when"
|  "while"
|  "with"
|] @keyword
|
|[
|  "("
|  ")"
|  "["
|  "]"
|  "{"
|  "}"
|  "[|"
|  "|]"
|  "[<"
|  "[>"
|  "[@@"
|  "[@"
|  "[%"
|] @punctuation.bracket
|
|(object_type
|  [
|    "<"
|    ">"
|  ] @punctuation.bracket)
|
|[
|  ","
|  "."
|  ";"
|  ":"
|  "="
|  "|"
|  "~"
|  "?"
|  "+"
|  "-"
|  "!"
|  ">"
|  "&"
|  "->"
|  ";;"
|  ":>"
|  "+="
|  ":="
|  ".."
|] @punctuation.delimiter
|
|[
|  (attribute)
|  (item_attribute)
|  (floating_attribute)
|  (extension)
|  (item_extension)
|  (quoted_extension)
|  (quoted_item_extension)
|] @attribute
|
|(attribute_id) @tag
|
|[
|  (comment)
|  (line_number_directive)
|  (directive)
|  (shebang)
|] @comment
""".trimMargin()
}
