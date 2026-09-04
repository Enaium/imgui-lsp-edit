package cn.enaium.lsp.edit.example

import cn.enaium.treesitter.languages.agda.TreeSitterAgda
import cn.enaium.treesitter.languages.bash.TreeSitterBash
import cn.enaium.treesitter.languages.c.TreeSitterC
import cn.enaium.treesitter.languages.cpp.TreeSitterCpp
import cn.enaium.treesitter.languages.csharp.TreeSitterCSharp
import cn.enaium.treesitter.languages.css.TreeSitterCss
import cn.enaium.treesitter.languages.diff.TreeSitterDiff
import cn.enaium.treesitter.languages.embeddedtemplate.TreeSitterEmbeddedTemplate
import cn.enaium.treesitter.languages.glsl.TreeSitterGlsl
import cn.enaium.treesitter.languages.go.TreeSitterGo
import cn.enaium.treesitter.languages.haskell.TreeSitterHaskell
import cn.enaium.treesitter.languages.html.TreeSitterHtml
import cn.enaium.treesitter.languages.java.TreeSitterJava
import cn.enaium.treesitter.languages.javascript.TreeSitterJavascript
import cn.enaium.treesitter.languages.json.TreeSitterJson
import cn.enaium.treesitter.languages.julia.TreeSitterJulia
import cn.enaium.treesitter.languages.kotlin.TreeSitterKotlin
import cn.enaium.treesitter.languages.lua.TreeSitterLua
import cn.enaium.treesitter.languages.markdown.TreeSitterMarkdown
import cn.enaium.treesitter.languages.ocaml.TreeSitterOcaml
import cn.enaium.treesitter.languages.php.TreeSitterPhp
import cn.enaium.treesitter.languages.properties.TreeSitterProperties
import cn.enaium.treesitter.languages.python.TreeSitterPython
import cn.enaium.treesitter.languages.regex.TreeSitterRegex
import cn.enaium.treesitter.languages.ruby.TreeSitterRuby
import cn.enaium.treesitter.languages.rust.TreeSitterRust
import cn.enaium.treesitter.languages.scala.TreeSitterScala
import cn.enaium.treesitter.languages.smali.TreeSitterSmali
import cn.enaium.treesitter.languages.toml.TreeSitterToml
import cn.enaium.treesitter.languages.tsx.TreeSitterTsx
import cn.enaium.treesitter.languages.typescript.TreeSitterTypescript
import cn.enaium.treesitter.languages.verilog.TreeSitterVerilog
import cn.enaium.treesitter.languages.xml.TreeSitterXml
import cn.enaium.treesitter.languages.yaml.TreeSitterYaml
import cn.enaium.lsp.edit.syntax.treesitter.HighlightQueries
import io.github.treesitter.ktreesitter.Language

/**
 * Every language available to the tree-sitter highlighter: a display name,
 * the grammar's native [Language] and the highlight query to run on it.
 */
enum class TreeSitterLanguageSpec(
    val id: String,
    val displayName: String,
) {
    AGDA("agda", "Agda"),
    BASH("bash", "Bash"),
    C("c", "C"),
    C_SHARP("c-sharp", "C#"),
    CPP("cpp", "C++"),
    CSS("css", "CSS"),
    DIFF("diff", "Diff"),
    EMBEDDED_TEMPLATE("embedded-template", "Embedded Template"),
    GLSL("glsl", "GLSL"),
    GO("go", "Go"),
    HASKELL("haskell", "Haskell"),
    HTML("html", "HTML"),
    JAVA("java", "Java"),
    JAVASCRIPT("javascript", "JavaScript"),
    JSON("json", "JSON"),
    JULIA("julia", "Julia"),
    KOTLIN("kotlin", "Kotlin"),
    LUA("lua", "Lua"),
    MARKDOWN("markdown", "Markdown"),
    OCAML("ocaml", "OCaml"),
    PHP("php", "PHP"),
    PROPERTIES("properties", "Properties"),
    PYTHON("python", "Python"),
    REGEX("regex", "Regex"),
    RUBY("ruby", "Ruby"),
    RUST("rust", "Rust"),
    SCALA("scala", "Scala"),
    SMALI("smali", "Smali"),
    TOML("toml", "TOML"),
    TSX("tsx", "TSX"),
    TYPESCRIPT("typescript", "TypeScript"),
    VERILOG("verilog", "Verilog"),
    XML("xml", "XML"),
    YAML("yaml", "YAML");

    /** The native grammar, loaded from the bundled library. */
    fun language(): Language = when (this) {
        AGDA -> Language(TreeSitterAgda.language())
        BASH -> Language(TreeSitterBash.language())
        C -> Language(TreeSitterC.language())
        C_SHARP -> Language(TreeSitterCSharp.language())
        CPP -> Language(TreeSitterCpp.language())
        CSS -> Language(TreeSitterCss.language())
        DIFF -> Language(TreeSitterDiff.language())
        EMBEDDED_TEMPLATE -> Language(TreeSitterEmbeddedTemplate.language())
        GLSL -> Language(TreeSitterGlsl.language())
        GO -> Language(TreeSitterGo.language())
        HASKELL -> Language(TreeSitterHaskell.language())
        HTML -> Language(TreeSitterHtml.language())
        JAVA -> Language(TreeSitterJava.language())
        JAVASCRIPT -> Language(TreeSitterJavascript.language())
        JSON -> Language(TreeSitterJson.language())
        JULIA -> Language(TreeSitterJulia.language())
        KOTLIN -> Language(TreeSitterKotlin.language())
        LUA -> Language(TreeSitterLua.language())
        MARKDOWN -> Language(TreeSitterMarkdown.language())
        OCAML -> Language(TreeSitterOcaml.language())
        PHP -> Language(TreeSitterPhp.language())
        PROPERTIES -> Language(TreeSitterProperties.language())
        PYTHON -> Language(TreeSitterPython.language())
        REGEX -> Language(TreeSitterRegex.language())
        RUBY -> Language(TreeSitterRuby.language())
        RUST -> Language(TreeSitterRust.language())
        SCALA -> Language(TreeSitterScala.language())
        SMALI -> Language(TreeSitterSmali.language())
        TOML -> Language(TreeSitterToml.language())
        TSX -> Language(TreeSitterTsx.language())
        TYPESCRIPT -> Language(TreeSitterTypescript.language())
        VERILOG -> Language(TreeSitterVerilog.language())
        XML -> Language(TreeSitterXml.language())
        YAML -> Language(TreeSitterYaml.language())
    }

    /** The highlight query source for this language. */
    val query: String get() = HighlightQueries.byName(id) ?: ""

    /** Sample source text from the grammar's test fixtures. */
    val sample: String get() = Samples.all[id] ?: ""
}
