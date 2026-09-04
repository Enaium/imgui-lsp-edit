// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for C++ (from zed highlights.scm). */
object CppQuery {
    val query: String = """
|(identifier) @variable
|
|(field_identifier) @property
|
|(namespace_identifier) @namespace
|
|(concept_definition
|  name: (identifier) @concept)
|
|(requires_clause
|  constraint: (template_type
|    name: (type_identifier) @concept))
|
|(call_expression
|  function: (qualified_identifier
|    name: (identifier) @function))
|
|(call_expression
|  (qualified_identifier
|    (identifier) @function.call))
|
|(call_expression
|  (qualified_identifier
|    (qualified_identifier
|      (identifier) @function.call)))
|
|(call_expression
|  (qualified_identifier
|    (qualified_identifier
|      (qualified_identifier
|        (identifier) @function.call))))
|
|((qualified_identifier
|  (qualified_identifier
|    (qualified_identifier
|      (qualified_identifier
|        (identifier) @function.call)))) @_parent
|  (#has-ancestor? @_parent call_expression))
|
|(call_expression
|  function: (identifier) @function)
|
|(call_expression
|  function: (field_expression
|    field: (field_identifier) @function))
|
|(preproc_function_def
|  name: (identifier) @function.special)
|
|(template_function
|  name: (identifier) @function)
|
|(template_method
|  name: (field_identifier) @function)
|
|(function_declarator
|  declarator: (identifier) @function)
|
|(function_declarator
|  declarator: (qualified_identifier
|    name: (identifier) @function))
|
|(function_declarator
|  declarator: (field_identifier) @function)
|
|(operator_name
|  (identifier)? @operator) @function
|
|(operator_name
|  "<=>" @operator.spaceship)
|
|(destructor_name
|  (identifier) @function)
|
|((namespace_identifier) @type
|  (#match? @type "^[A-Z]"))
|
|(auto) @type
|
|(type_identifier) @type
|
|type: (primitive_type) @type.builtin
|
|(sized_type_specifier) @type.builtin
|
|(attribute_specifier) @attribute
|
|(attribute_specifier
|  (argument_list
|    (identifier) @attribute))
|
|(attribute
|  prefix: (identifier) @attribute)
|
|(attribute
|  name: (identifier) @attribute)
|
|((identifier) @constant.builtin
|  (#match? @constant.builtin "^_*[A-Z][A-Z\\d_]*${'$'}"))
|
|(statement_identifier) @label
|
|(this) @variable.builtin
|
|"static_assert" @function.builtin
|
|[
|  "break"
|  "case"
|  "catch"
|  "co_await"
|  "co_return"
|  "co_yield"
|  "continue"
|  "default"
|  "do"
|  "else"
|  "for"
|  "goto"
|  "if"
|  "return"
|  "switch"
|  "throw"
|  "try"
|  "while"
|] @keyword.control
|
|[
|  "#define"
|  "#elif"
|  "#elifdef"
|  "#elifndef"
|  "#else"
|  "#endif"
|  "#if"
|  "#ifdef"
|  "#ifndef"
|  "#include"
|  (preproc_directive)
|] @keyword.preproc @preproc
|
|(comment) @comment
|
|[
|  (true)
|  (false)
|] @boolean
|
|[
|  (null)
|  "nullptr"
|] @constant.builtin
|
|(number_literal) @number
|
|[
|  (string_literal)
|  (system_lib_string)
|  (char_literal)
|  (raw_string_literal)
|] @string
|
|(escape_sequence) @string.escape
|
|[
|  ","
|  ":"
|  "::"
|  ";"
|  (raw_string_delimiter)
|] @punctuation.delimiter
|
|[
|  "{"
|  "}"
|  "("
|  ")"
|  "["
|  "]"
|] @punctuation.bracket
|
|[
|  "."
|  ".*"
|  "->*"
|  "~"
|  "-"
|  "--"
|  "-="
|  "->"
|  "="
|  "!"
|  "!="
|  "|"
|  "|="
|  "||"
|  "^"
|  "^="
|  "&"
|  "&="
|  "&&"
|  "+"
|  "++"
|  "+="
|  "*"
|  "*="
|  "/"
|  "/="
|  "%"
|  "%="
|  "<<"
|  "<<="
|  ">>"
|  ">>="
|  "<"
|  "=="
|  ">"
|  "<="
|  ">="
|  "?"
|  "and"
|  "and_eq"
|  "bitand"
|  "bitor"
|  "compl"
|  "not"
|  "not_eq"
|  "or"
|  "or_eq"
|  "xor"
|  "xor_eq"
|] @operator
|
|"<=>" @operator.spaceship
|
|(binary_expression
|  operator: "<=>" @operator.spaceship)
|
|(conditional_expression
|  ":" @operator)
|
|(user_defined_literal
|  (literal_suffix) @operator)
""".trimMargin()
}
