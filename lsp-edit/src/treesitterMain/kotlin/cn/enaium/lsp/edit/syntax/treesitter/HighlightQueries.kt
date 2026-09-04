// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter

import cn.enaium.lsp.edit.syntax.treesitter.query.*

/**
 * Registry of highlight queries, one object per language.
 * Each query captures nodes into the standard capture names;
 * [TreeSitterHighlighter] maps them to palette slots.
 */
object HighlightQueries {
    /** (language id, query source). */
    val all: Map<String, String> = mapOf(
        "agda" to AgdaQuery.query,
        "bash" to BashQuery.query,
        "c" to CQuery.query,
        "c-sharp" to CSharpQuery.query,
        "cpp" to CppQuery.query,
        "css" to CssQuery.query,
        "diff" to DiffQuery.query,
        "embedded-template" to EmbeddedTemplateQuery.query,
        "glsl" to GlslQuery.query,
        "go" to GoQuery.query,
        "haskell" to HaskellQuery.query,
        "html" to HtmlQuery.query,
        "java" to JavaQuery.query,
        "javascript" to JavascriptQuery.query,
        "json" to JsonQuery.query,
        "julia" to JuliaQuery.query,
        "kotlin" to KotlinQuery.query,
        "lua" to LuaQuery.query,
        "markdown" to MarkdownQuery.query,
        "ocaml" to OcamlQuery.query,
        "php" to PhpQuery.query,
        "properties" to PropertiesQuery.query,
        "python" to PythonQuery.query,
        "regex" to RegexQuery.query,
        "ruby" to RubyQuery.query,
        "rust" to RustQuery.query,
        "scala" to ScalaQuery.query,
        "smali" to SmaliQuery.query,
        "toml" to TomlQuery.query,
        "tsx" to TsxQuery.query,
        "typescript" to TypescriptQuery.query,
        "verilog" to VerilogQuery.query,
        "xml" to XmlQuery.query,
        "yaml" to YamlQuery.query,
    )

    /** Query source for [id], or null when unknown. */
    fun byName(id: String): String? = all[id]
}
