// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Smali (from zed highlights.scm). */
object SmaliQuery {
    val query: String = """
|(comment) @comment
|(float) @number
|(number) @number
|["L" "abstract" "aget" "annotation" "aput" "blacklist" "bridge" "build" "const" "constructor" "enum" "false" "final" "goto" "greylist" "iget" "interface" "iput" "move" "native" "nop" "private" "protected" "public" "return" "runtime" "sget" "sput" "static" "strictfp" "synchronized" "synthetic" "system" "throw" "transient" "true" "varargs" "volatile" "whitelist"] @keyword
|(method_identifier) @function
|(class_identifier) @type
""".trimMargin()
}
