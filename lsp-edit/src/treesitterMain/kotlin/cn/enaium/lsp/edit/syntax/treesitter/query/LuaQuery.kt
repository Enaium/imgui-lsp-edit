// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Lua (from zed highlights.scm). */
object LuaQuery {
    val query: String = """
|[
|  "do"
|  "else"
|  "elseif"
|  "end"
|  "for"
|  "function"
|  "goto"
|  "if"
|  "in"
|  "local"
|  "global"
|  "repeat"
|  "return"
|  "then"
|  "until"
|  "while"
|  (break_statement)
|] @keyword
|
|[
|  "and"
|  "not"
|  "or"
|] @keyword.operator
|
|[
|  "+"
|  "-"
|  "*"
|  "/"
|  "%"
|  "^"
|  "#"
|  "=="
|  "~="
|  "<="
|  ">="
|  "<"
|  ">"
|  "="
|  "&"
|  "~"
|  "|"
|  "<<"
|  ">>"
|  "//"
|  ".."
|] @operator
|
|[
|  ";"
|  ":"
|  ","
|  "."
|] @punctuation.delimiter
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
|(identifier) @variable
|
|((identifier) @variable.special
|  (#eq? @variable.special "self"))
|
|(variable_list
|  attribute: (attribute
|    ([
|      "<"
|      ">"
|    ] @punctuation.bracket
|      (identifier) @attribute)))
|
|((identifier) @constant
|  (#match? @constant "^[A-Z][A-Z_0-9]*${'$'}"))
|
|(vararg_expression) @constant
|
|(nil) @constant.builtin
|
|[
|  (false)
|  (true)
|] @boolean
|
|(field
|  name: (identifier) @property)
|
|(dot_index_expression
|  field: (identifier) @property)
|
|(table_constructor
|  [
|    "{"
|    "}"
|  ] @constructor)
|
|(parameters
|  (identifier) @parameter)
|
|(function_call
|  name: [
|    (identifier) @function
|    (dot_index_expression
|      field: (identifier) @function)
|  ])
|
|(function_declaration
|  name: [
|    (identifier) @function.definition
|    (dot_index_expression
|      field: (identifier) @function.definition)
|  ])
|
|(method_index_expression
|  method: (identifier) @function.method)
|
|(function_call
|  (identifier) @function.builtin
|  (#any-of? @function.builtin
|    "assert" "collectgarbage" "dofile" "error" "getfenv" "getmetatable" "ipairs" "load" "loadfile"
|    "loadstring" "module" "next" "pairs" "pcall" "print" "rawequal" "rawget" "rawset" "require"
|    "select" "setfenv" "setmetatable" "tonumber" "tostring" "type" "unpack" "xpcall"))
|
|(comment) @comment
|
|(hash_bang_line) @preproc
|
|(number) @number
|
|(string) @string
|
|(escape_sequence) @string.escape
""".trimMargin()
}
