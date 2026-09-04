// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for C# (from zed highlights.scm). */
object CSharpQuery {
    val query: String = """
|(comment) @comment
|(raw_string_literal) @string
|(string_literal) @string
|(string_literal_encoding) @string
|(verbatim_string_literal) @string
|(integer_literal) @number
|["Cdecl" "Fastcall" "Stdcall" "Thiscall" "__makeref" "__reftype" "__refvalue" "abstract" "add" "alias" "and" "annotations" "as" "ascending" "assembly" "async" "await" "base" "break" "by" "case" "catch" "checked" "checksum" "class" "const" "continue" "default" "delegate" "descending" "disable" "do" "else" "enable" "enum" "equals" "event" "explicit" "extern" "false" "field" "file" "finally" "fixed" "for" "foreach" "from" "get" "global" "goto" "group" "hidden" "if" "implicit" "in" "init" "interface" "internal" "into" "is" "join" "let" "lock" "managed" "method" "module" "namespace" "new" "not" "notnull" "on" "operator" "or" "orderby" "out" "override" "param" "params" "partial" "private" "property" "protected" "public" "readonly" "record" "ref" "remove" "required" "restore" "return" "scoped" "sealed" "select" "set" "sizeof" "stackalloc" "static" "struct" "switch" "this" "throw" "true" "try" "type" "typeof" "unchecked" "unmanaged" "unsafe" "using" "var" "virtual" "volatile" "warning" "warnings" "when" "where" "while" "with" "yield"] @keyword
|(method_declaration name: (identifier) @function)
|(class_declaration name: (identifier) @type)
|(interface_declaration name: (identifier) @type)
|(enum_declaration name: (identifier) @type)
""".trimMargin()
}
