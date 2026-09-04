// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for GLSL (from zed highlights.scm). */
object GlslQuery {
    val query: String = """
|(comment) @comment
|(char_literal) @string
|(concatenated_string) @string
|(string_literal) @string
|(system_lib_string) @string
|(number_literal) @number
|["NULL" "_Alignas" "_Alignof" "_Atomic" "_Generic" "_Nonnull" "_Noreturn" "__alignof" "__alignof__" "__asm" "__asm__" "__attribute" "__attribute__" "__based" "__cdecl" "__clrcall" "__declspec" "__except" "__extension__" "__fastcall" "__finally" "__forceinline" "__inline" "__inline__" "__leave" "__restrict__" "__stdcall" "__thiscall" "__thread" "__try" "__unaligned" "__vectorcall" "__volatile__" "_alignof" "_unaligned" "alignas" "alignof" "asm" "attribute" "auto" "break" "buffer" "callableDataEXT" "callableDataInNV" "callableDataNV" "case" "centroid" "coherent" "const" "constexpr" "continue" "default" "defined" "disable" "do" "else" "enable" "enum" "extern" "flat" "for" "goto" "highp" "hitAttributeEXT" "hitAttributeNV" "if" "in" "inline" "inout" "invariant" "layout" "long" "lowp" "mediump" "noperspective" "noreturn" "nullptr" "offsetof" "out" "patch" "precise" "precision" "rayPayloadEXT" "rayPayloadInEXT" "rayPayloadInNV" "rayPayloadNV" "readonly" "register" "require" "restrict" "return" "sample" "shaderRecordEXT" "shaderRecordNV" "shared" "short" "signed" "sizeof" "smooth" "static" "struct" "subroutine" "switch" "thread_local" "typedef" "uniform" "union" "unsigned" "varying" "volatile" "warn" "while" "writeonly"] @keyword
|(function_declarator declarator: (identifier) @function)
|(call_expression function: (identifier) @function)
|(primitive_type) @type
""".trimMargin()
}
