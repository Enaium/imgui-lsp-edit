// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Verilog (from zed highlights.scm). */
object VerilogQuery {
    val query: String = """
|(comment) @comment
|(double_quoted_string) @string
|(dpi_spec_string) @string
|(string_literal) @string
|(binary_number) @number
|(decimal_number) @number
|(finish_number) @number
|(fixed_point_number) @number
|(hex_number) @number
|(integer_atom_type) @number
|(integer_vector_type) @number
|(integral_number) @number
|(non_integer_type) @number
|(octal_number) @number
|(real_number) @number
|(unsigned_number) @number
|["accept_on" "alias" "always" "always_comb" "always_ff" "always_latch" "and" "assert" "assign" "assume" "automatic" "before" "begin" "bind" "bins" "binsof" "bit" "break" "buf" "bufif0" "bufif1" "byte" "case" "casex" "casez" "chandle" "checker" "class" "clocking" "cmos" "const" "constraint" "context" "continue" "cover" "covergroup" "coverpoint" "cross" "deassign" "default" "defparam" "directive_define" "directive_else" "directive_elsif" "directive_endif" "directive_ifdef" "directive_ifndef" "directive_line" "directive_undef" "disable" "dist" "do" "edge" "else" "end" "endcase" "endchecker" "endclass" "endclocking" "endfunction" "endgenerate" "endgroup" "endinterface" "endmodule" "endpackage" "endprimitive" "endprogram" "endproperty" "endsequence" "endspecify" "endtable" "endtask" "enum" "event" "eventually" "expect" "export" "extends" "extern" "final" "first_match" "for" "force" "foreach" "forever" "fork" "forkjoin" "fs" "function" "generate" "genvar" "global" "highz0" "highz1" "if" "iff" "ifnone" "ignore_bins" "illegal_bins" "implements" "implies" "import" "initial" "inout" "input" "inside" "int" "integer" "interconnect" "interface" "intersect" "join" "join_any" "join_none" "large" "let" "local" "localparam" "logic" "longint" "macromodule" "matches" "medium" "modport" "module" "ms" "nand" "negedge" "nettype" "new" "nexttime" "nmos" "none" "nor" "noshowcancelled" "not" "notif0" "notif1" "ns" "null" "option" "or" "output" "package" "packed" "parameter" "pmos" "posedge" "primitive" "priority" "program" "property" "protected" "ps" "pull0" "pull1" "pulldown" "pullup" "pure" "rand" "randc" "randcase" "randomize" "rcmos" "real" "realtime" "ref" "reg" "reject_on" "release" "repeat" "restrict" "return" "rnmos" "rpmos" "rtran" "rtranif0" "rtranif1" "s" "s_always" "s_eventually" "s_nexttime" "s_until" "s_until_with" "sample" "scalared" "sequence" "shortint" "shortreal" "showcancelled" "signed" "small" "soft" "solve" "specify" "specparam" "static" "std" "string" "strong" "strong0" "strong1" "struct" "super" "supply0" "supply1" "sync_accept_on" "sync_reject_on" "table" "tagged" "task" "this" "throughout" "time" "timeprecision" "timeunit" "tran" "tranif0" "tranif1" "tri" "tri0" "tri1" "triand" "trior" "trireg" "type" "type_option" "typedef" "union" "unique" "unique0" "unsigned" "until" "until_with" "untyped" "us" "uwire" "var" "vectored" "virtual" "void" "wait" "wait_order" "wand" "weak" "weak0" "weak1" "while" "wildcard" "wire" "with" "within" "wor" "xnor" "xor"] @keyword
|(function_identifier) @function
|(class_identifier) @type
""".trimMargin()
}
