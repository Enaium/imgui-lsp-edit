// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for JavaScript (from zed highlights.scm). */
object JavascriptQuery {
    val query: String = """
|(identifier) @variable
|
|(call_expression
|  function: (member_expression
|    object: (identifier) @type
|    (#any-of? @type
|      "Promise" "Array" "Object" "Map" "Set" "WeakMap" "WeakSet" "Date" "Error" "TypeError"
|      "RangeError" "SyntaxError" "ReferenceError" "EvalError" "URIError" "RegExp" "Function"
|      "Number" "String" "Boolean" "Symbol" "BigInt" "Proxy" "ArrayBuffer" "DataView")))
|
|(property_identifier) @property
|
|(shorthand_property_identifier) @property
|
|(shorthand_property_identifier_pattern) @property
|
|(private_property_identifier) @property
|
|(call_expression
|  function: (identifier) @function)
|
|(call_expression
|  function: (member_expression
|    property: [
|      (property_identifier)
|      (private_property_identifier)
|    ] @function.method))
|
|(new_expression
|  constructor: (identifier) @type.class)
|
|(function_expression
|  name: (identifier) @function)
|
|(function_declaration
|  name: (identifier) @function)
|
|(method_definition
|  name: [
|    (property_identifier)
|    (private_property_identifier)
|  ] @function.method)
|
|(method_definition
|  name: (property_identifier) @constructor
|  (#eq? @constructor "constructor"))
|
|(pair
|  key: [
|    (property_identifier)
|    (private_property_identifier)
|  ] @function.method
|  value: [
|    (function_expression)
|    (arrow_function)
|  ])
|
|(assignment_expression
|  left: (member_expression
|    property: [
|      (property_identifier)
|      (private_property_identifier)
|    ] @function.method)
|  right: [
|    (function_expression)
|    (arrow_function)
|  ])
|
|(variable_declarator
|  name: (identifier) @function
|  value: [
|    (function_expression)
|    (arrow_function)
|  ])
|
|(assignment_expression
|  left: (identifier) @function
|  right: [
|    (function_expression)
|    (arrow_function)
|  ])
|
|(catch_clause
|  parameter: (identifier) @variable.parameter)
|
|(arrow_function
|  parameter: (identifier) @variable.parameter)
|
|([
|  (identifier)
|  (shorthand_property_identifier)
|  (shorthand_property_identifier_pattern)
|] @constant
|  (#match? @constant "^_*[A-Z_][A-Z\\d_]*${'$'}"))
|
|(this) @variable.special
|
|(super) @variable.special
|
|[
|  (null)
|  (undefined)
|] @constant.builtin
|
|[
|  (true)
|  (false)
|] @boolean
|
|(comment) @comment
|
|(hash_bang_line) @comment
|
|[
|  (string)
|  (template_string)
|] @string
|
|(escape_sequence) @string.escape
|
|(regex) @string.regex
|
|(regex_flags) @keyword.operator.regex
|
|(number) @number
|
|[
|  "-"
|  "--"
|  "-="
|  "+"
|  "++"
|  "+="
|  "*"
|  "*="
|  "**"
|  "**="
|  "/"
|  "/="
|  "%"
|  "%="
|  "<"
|  "<="
|  "<<"
|  "<<="
|  "="
|  "=="
|  "==="
|  "!"
|  "!="
|  "!=="
|  "=>"
|  ">"
|  ">="
|  ">>"
|  ">>="
|  ">>>"
|  ">>>="
|  "~"
|  "^"
|  "&"
|  "|"
|  "^="
|  "&="
|  "|="
|  "&&"
|  "||"
|  "??"
|  "&&="
|  "||="
|  "??="
|  "..."
|] @operator
|
|(regex
|  "/" @string.regex)
|
|[
|  "("
|  ")"
|  "["
|  "]"
|  "{"
|  "}"
|] @punctuation.bracket
|
|(ternary_expression
|  [
|    "?"
|    ":"
|  ] @operator)
|
|[
|  "export"
|  "from"
|  "import"
|] @keyword.import
|
|[
|  "await"
|  "break"
|  "case"
|  "catch"
|  "continue"
|  "do"
|  "else"
|  "finally"
|  "for"
|  "if"
|  "return"
|  "switch"
|  "throw"
|  "try"
|  "while"
|  "yield"
|] @keyword.control
|
|(switch_default
|  "default" @keyword.control)
|
|(template_substitution
|  "${'$'}{" @punctuation.special
|  "}" @punctuation.special) @embedded
|
|(decorator
|  "@" @punctuation.special)
|
|(jsx_opening_element
|  [
|    (identifier) @type @tag.component.jsx
|    (member_expression
|      object: (identifier) @type @tag.component.jsx
|      property: (property_identifier) @type @tag.component.jsx)
|    (member_expression
|      object: (member_expression
|        object: (identifier) @type @tag.component.jsx
|        property: (property_identifier) @type @tag.component.jsx)
|      property: (property_identifier) @type @tag.component.jsx)
|    (member_expression
|      object: (member_expression
|        object: (member_expression
|          object: (identifier) @type @tag.component.jsx
|          property: (property_identifier) @type @tag.component.jsx)
|        property: (property_identifier) @type @tag.component.jsx)
|      property: (property_identifier) @type @tag.component.jsx)
|  ])
|
|(jsx_closing_element
|  [
|    (identifier) @type @tag.component.jsx
|    (member_expression
|      object: (identifier) @type @tag.component.jsx
|      property: (property_identifier) @type @tag.component.jsx)
|    (member_expression
|      object: (member_expression
|        object: (identifier) @type @tag.component.jsx
|        property: (property_identifier) @type @tag.component.jsx)
|      property: (property_identifier) @type @tag.component.jsx)
|    (member_expression
|      object: (member_expression
|        object: (member_expression
|          object: (identifier) @type @tag.component.jsx
|          property: (property_identifier) @type @tag.component.jsx)
|        property: (property_identifier) @type @tag.component.jsx)
|      property: (property_identifier) @type @tag.component.jsx)
|  ])
|
|(jsx_self_closing_element
|  [
|    (identifier) @type @tag.component.jsx
|    (member_expression
|      object: (identifier) @type @tag.component.jsx
|      property: (property_identifier) @type @tag.component.jsx)
|    (member_expression
|      object: (member_expression
|        object: (identifier) @type @tag.component.jsx
|        property: (property_identifier) @type @tag.component.jsx)
|      property: (property_identifier) @type @tag.component.jsx)
|    (member_expression
|      object: (member_expression
|        object: (member_expression
|          object: (identifier) @type @tag.component.jsx
|          property: (property_identifier) @type @tag.component.jsx)
|        property: (property_identifier) @type @tag.component.jsx)
|      property: (property_identifier) @type @tag.component.jsx)
|  ])
|
|(jsx_opening_element
|  (identifier) @tag.jsx
|  (#match? @tag.jsx "^[a-z][^.]*${'$'}"))
|
|(jsx_closing_element
|  (identifier) @tag.jsx
|  (#match? @tag.jsx "^[a-z][^.]*${'$'}"))
|
|(jsx_self_closing_element
|  (identifier) @tag.jsx
|  (#match? @tag.jsx "^[a-z][^.]*${'$'}"))
|
|(jsx_attribute
|  (property_identifier) @attribute.jsx)
|
|(jsx_opening_element
|  ([
|    "<"
|    ">"
|  ]) @punctuation.bracket.jsx)
|
|(jsx_closing_element
|  ([
|    "</"
|    ">"
|  ]) @punctuation.bracket.jsx)
|
|(jsx_self_closing_element
|  ([
|    "<"
|    "/>"
|  ]) @punctuation.bracket.jsx)
|
|(jsx_attribute
|  "=" @punctuation.delimiter.jsx)
|
|(jsx_text) @text.jsx
|
|(html_character_reference) @string.special
""".trimMargin()
}
