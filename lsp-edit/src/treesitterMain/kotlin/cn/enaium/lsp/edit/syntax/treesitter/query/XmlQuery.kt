// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for XML (from zed highlights.scm). */
object XmlQuery {
    val query: String = """
|(Comment) @comment
|(StringType) @string
|["ANY" "ATTLIST" "CDATA" "DOCTYPE" "ELEMENT" "EMPTY" "ENTITY" "NDATA" "NOTATION" "PUBLIC" "SYSTEM" "encoding" "no" "standalone" "version" "xml" "yes"] @keyword
|(STag) @keyword
|(ETag) @keyword
|(EmptyElemTag) @keyword
|(Attribute) @property
|(AttValue) @string
|(Comment) @comment
|(doctypedecl) @keyword
|(XMLDecl) @keyword
|(XMLDecl (VersionNum) @string)
|(XMLDecl (EncName) @string)
""".trimMargin()
}
