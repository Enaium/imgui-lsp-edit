// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Kotlin (from zed highlights.scm). */
object KotlinQuery {
    val query: String = """
|(simple_identifier) @variable
|
|((simple_identifier) @variable.builtin
|  (#eq? @variable.builtin "it"))
|
|((simple_identifier) @variable.builtin
|  (#eq? @variable.builtin "field"))
|
|(this_expression) @variable.builtin
|
|(super_expression) @variable.builtin
|
|(class_parameter
|  (simple_identifier) @property)
|
|(class_body
|  (property_declaration
|    (variable_declaration
|      (simple_identifier) @property)))
|
|(_
|  (navigation_suffix
|    (simple_identifier) @property))
|
|(enum_entry
|  (simple_identifier) @constant)
|
|(type_identifier) @type
|
|((type_identifier) @type.builtin
|  (#any-of? @type.builtin
|    "Byte" "Short" "Int" "Long" "UByte" "UShort" "UInt" "ULong" "Float" "Double" "Boolean" "Char"
|    "String" "Array" "ByteArray" "ShortArray" "IntArray" "LongArray" "UByteArray" "UShortArray"
|    "UIntArray" "ULongArray" "FloatArray" "DoubleArray" "BooleanArray" "CharArray" "Map" "Set"
|    "List" "EmptyMap" "EmptySet" "EmptyList" "MutableMap" "MutableSet" "MutableList"))
|
|(package_header
|  .
|  (identifier)) @namespace
|
|(import_header
|  "import" @include)
|
|(label) @label
|
|(function_declaration
|  (simple_identifier) @function)
|
|(getter
|  "get" @function.builtin)
|
|(setter
|  "set" @function.builtin)
|
|(primary_constructor) @constructor
|
|(secondary_constructor
|  "constructor" @constructor)
|
|(constructor_invocation
|  (user_type
|    (type_identifier) @constructor))
|
|(anonymous_initializer
|  "init" @constructor)
|
|(parameter
|  (simple_identifier) @parameter)
|
|(parameter_with_optional_type
|  (simple_identifier) @parameter)
|
|(lambda_literal
|  (lambda_parameters
|    (variable_declaration
|      (simple_identifier) @parameter)))
|
|(call_expression
|  (simple_identifier) @function)
|
|(call_expression
|  (navigation_expression
|    (navigation_suffix
|      (simple_identifier) @function)))
|
|(call_expression
|  (simple_identifier) @function.builtin
|  (#any-of? @function.builtin
|    "arrayOf" "arrayOfNulls" "byteArrayOf" "shortArrayOf" "intArrayOf" "longArrayOf" "ubyteArrayOf"
|    "ushortArrayOf" "uintArrayOf" "ulongArrayOf" "floatArrayOf" "doubleArrayOf" "booleanArrayOf"
|    "charArrayOf" "emptyArray" "mapOf" "setOf" "listOf" "emptyMap" "emptySet" "emptyList"
|    "mutableMapOf" "mutableSetOf" "mutableListOf" "print" "println" "error" "TODO" "run"
|    "runCatching" "repeat" "lazy" "lazyOf" "enumValues" "enumValueOf" "assert" "check"
|    "checkNotNull" "require" "requireNotNull" "with" "suspend" "synchronized"))
|
|[
|  (line_comment)
|  (multiline_comment)
|  (shebang_line)
|] @comment
|
|((multiline_comment) @comment.doc
|  (#match? @comment.doc "^/\\*\\*"))
|
|(real_literal) @float
|
|[
|  (integer_literal)
|  (long_literal)
|  (hex_literal)
|  (bin_literal)
|  (unsigned_literal)
|] @number
|
|(character_literal) @character
|
|(string_literal) @string
|
|(character_escape_seq) @string.escape
|
|(call_expression
|  (navigation_expression
|    (string_literal) @string.regex
|    (navigation_suffix
|      ((simple_identifier) @_function
|        (#eq? @_function "toRegex")))))
|
|(call_expression
|  ((simple_identifier) @_function
|    (#eq? @_function "Regex"))
|  (call_suffix
|    (value_arguments
|      (value_argument
|        (string_literal) @string.regex))))
|
|(call_expression
|  (navigation_expression
|    ((simple_identifier) @_class
|      (#eq? @_class "Regex"))
|    (navigation_suffix
|      ((simple_identifier) @_function
|        (#eq? @_function "fromLiteral"))))
|  (call_suffix
|    (value_arguments
|      (value_argument
|        (string_literal) @string.regex))))
|
|(type_alias
|  "typealias" @keyword)
|
|[
|  (class_modifier)
|  (member_modifier)
|  (function_modifier)
|  (property_modifier)
|  (platform_modifier)
|  (variance_modifier)
|  (parameter_modifier)
|  (visibility_modifier)
|  (reification_modifier)
|  (inheritance_modifier)
|] @keyword
|
|[
|  "if"
|  "else"
|  "when"
|  "for"
|  "while"
|  "do"
|  "try"
|  "catch"
|  "throw"
|  "finally"
|  "val"
|  "var"
|  "enum"
|  "class"
|  "object"
|  "interface"
|  "companion"
|  "package"
|  "import"
|] @keyword
|
|"fun" @keyword.function
|
|(jump_expression) @keyword.return
|
|(annotation
|  "@" @attribute
|  (use_site_target)? @attribute)
|
|(annotation
|  (user_type
|    (type_identifier) @attribute))
|
|(annotation
|  (constructor_invocation
|    (user_type
|      (type_identifier) @attribute)))
|
|(file_annotation
|  "@" @attribute
|  "file" @attribute
|  ":" @attribute)
|
|(file_annotation
|  (user_type
|    (type_identifier) @attribute))
|
|(file_annotation
|  (constructor_invocation
|    (user_type
|      (type_identifier) @attribute)))
|
|[
|  "!"
|  "!="
|  "!=="
|  "="
|  "=="
|  "==="
|  ">"
|  ">="
|  "<"
|  "<="
|  "||"
|  "&&"
|  "+"
|  "++"
|  "+="
|  "-"
|  "--"
|  "-="
|  "*"
|  "*="
|  "/"
|  "/="
|  "%"
|  "%="
|  "?."
|  "?:"
|  "!!"
|  "is"
|  "in"
|  "as"
|  "as?"
|  ".."
|  "->"
|] @operator
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
|[
|  "."
|  ","
|  ";"
|  ":"
|  "::"
|] @punctuation.delimiter
""".trimMargin()
}
