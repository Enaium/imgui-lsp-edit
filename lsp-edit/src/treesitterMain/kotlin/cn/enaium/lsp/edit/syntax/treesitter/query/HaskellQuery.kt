// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Haskell (from zed highlights.scm). */
object HaskellQuery {
    val query: String = """
|(variable) @variable
|
|(decl/function
|  patterns: (patterns
|    (_) @variable.parameter))
|
|(expression/lambda
|  (_)+ @variable.parameter
|  "->")
|
|(decl/function
|  (infix
|    (pattern) @variable.parameter))
|
|(integer) @number
|
|(negation) @number
|
|(expression/literal
|  (float)) @number.float
|
|(char) @string
|
|(string) @string
|
|(comment) @comment
|
|(haddock) @comment.documentation
|
|[
|  "("
|  ")"
|  "{"
|  "}"
|  "["
|  "]"
|] @punctuation.bracket
|
|[
|  ","
|  ";"
|] @punctuation.delimiter
|
|[
|  "forall"
|] @keyword.repeat
|
|(pragma) @keyword.directive
|
|[
|  "if"
|  "then"
|  "else"
|  "case"
|  "of"
|] @keyword.conditional
|
|[
|  "import"
|  "qualified"
|  "module"
|] @keyword.import
|
|[
|  (operator)
|  (constructor_operator)
|  (all_names)
|  "."
|  ".."
|  "="
|  "|"
|  "::"
|  "=>"
|  "->"
|  "<-"
|  "\\"
|  "`"
|  "@"
|] @operator
|
|(wildcard) @string.special
|
|(module
|  (module_id) @title)
|
|[
|  "where"
|  "let"
|  "in"
|  "class"
|  "instance"
|  "pattern"
|  "data"
|  "newtype"
|  "family"
|  "type"
|  "as"
|  "hiding"
|  "deriving"
|  "via"
|  "stock"
|  "anyclass"
|  "do"
|  "mdo"
|  "rec"
|  "infix"
|  "infixl"
|  "infixr"
|] @keyword
|
|(decl/signature
|  [
|    name: (variable) @function
|    names: (binding_list
|      (variable) @function)
|  ])
|
|(decl/function
|  [
|    name: (variable) @function
|    names: (binding_list
|      (variable) @function)
|  ])
|
|(decl/bind
|  [
|    name: (variable) @function
|    names: (binding_list
|      (variable) @function)
|  ])
|
|(decl/bind
|  name: (variable) @variable)
|
|(decl/signature
|  name: (variable) @variable
|  type: (type))
|
|((decl/signature
|  name: (variable) @_name
|  type: (type))
|  .
|  (decl
|    [
|      (signature
|        name: (variable) @variable)
|      (function
|        name: (variable) @variable)
|      (bind
|        name: (variable) @variable)
|    ])
|  match: (_)
|  (#eq? @_name @variable))
|
|(decl/signature
|  name: (variable) @function
|  type: (type/apply
|    constructor: (name) @_type)
|  (#eq? @_type "IO"))
|
|((decl/signature
|  name: (variable) @_name
|  type: (type/apply
|    constructor: (name) @_type)
|  (#eq? @_type "IO"))
|  .
|  (decl
|    [
|      (signature
|        name: (variable) @function)
|      (function
|        name: (variable) @function)
|      (bind
|        name: (variable) @function)
|    ])
|  match: (_)
|  (#eq? @_name @function))
|
|((decl/signature) @function
|  .
|  (decl/function
|    name: (variable) @function))
|
|(decl/bind
|  name: (variable) @function
|  (match
|    expression: (expression/lambda)))
|
|(view_pattern
|  [
|    (expression/variable) @function.call
|    (expression/qualified
|      (variable) @function.call)
|  ])
|
|(infix_id
|  [
|    (variable) @operator
|    (qualified
|      (variable) @operator)
|  ])
|
|(infix
|  left_operand: [
|    (variable) @function.call
|    (qualified
|      ((module) @title
|        (variable) @function.call))
|  ])
|
|((expression/variable) @variable
|  .
|  (operator))
|
|((operator)
|  .
|  [
|    (expression/variable) @variable
|    (expression/qualified
|      (variable) @variable)
|  ])
|
|(function
|  (infix
|    left_operand: [
|      (variable) @variable
|      (qualified
|        ((module) @title
|          (variable) @variable))
|    ])
|  match: (match))
|
|([
|  (expression/variable) @function.call
|  (expression/qualified
|    (variable) @function.call)
|]
|  .
|  (operator) @_op
|  (#any-of? @_op "${'$'}" "<${'$'}>" ">>=" "=<<"))
|
|((infix
|  [
|    (operator)
|    (infix_id
|      (variable))
|  ] ; infix or `func`
|  .
|  [
|    (variable) @function.call
|    (qualified
|      (variable) @function.call)
|  ])
|  .
|  (operator) @_op
|  (#any-of? @_op "${'$'}" "<${'$'}>" "=<<"))
|
|([
|  (expression/variable) @function
|  (expression/qualified
|    (variable) @function)
|]
|  .
|  (operator) @_op
|  (#any-of? @_op "." ">>>" "***" ">=>" "<=<"))
|
|((infix
|  [
|    (operator)
|    (infix_id
|      (variable))
|  ] ; infix or `func`
|  .
|  [
|    (variable) @function
|    (qualified
|      (variable) @function)
|  ])
|  .
|  (operator) @_op
|  (#any-of? @_op "." ">>>" "***" ">=>" "<=<"))
|
|((operator) @_op
|  .
|  [
|    (expression/variable) @function
|    (expression/qualified
|      (variable) @function)
|  ]
|  (#any-of? @_op "." ">>>" "***" ">=>" "<=<"))
|
|(decl/function
|  name: (variable) @function
|  (match
|    expression: (infix
|      operator: (operator) @_op
|      (#any-of? @_op "." ">>>" "***" ">=>" "<=<"))))
|
|(apply
|  [
|    (expression/variable) @function.call
|    (expression/qualified
|      (variable) @function.call)
|  ])
|
|(apply
|  .
|  (expression/parens
|    (infix
|      [
|        (variable) @function.call
|        (qualified
|          (variable) @function.call)
|      ]
|      .
|      (operator))))
|
|(apply
|  .
|  (expression/parens
|    (infix
|      (operator)
|      .
|      [
|        (variable) @function.call
|        (qualified
|          (variable) @function.call)
|      ])))
|
|(apply
|  (_)
|  .
|  [
|    (expression/variable) @variable
|    (expression/qualified
|      (variable) @variable)
|  ])
|
|(decl/bind
|  name: (variable) @function
|  (#eq? @function "main"))
|
|(signature
|  pattern: (pattern/variable) @function
|  type: (function))
|
|(decl/signature
|  name: (variable) @function
|  type: (function))
|
|((decl/signature
|  name: (variable) @_name
|  type: (quantified_type))
|  .
|  (decl/bind
|    (variable) @function)
|  (#eq? @function @_name))
|
|(bind
|  name: (variable) @function
|  match: (match
|    expression: (constructor)))
|
|(bind
|  name: (variable) @function
|  match: (match
|    expression: (infix
|      operator: (operator) @_op
|      (#eq? @_op "."))))
|
|(name) @type
|
|(type/unit) @type
|
|(type/unit
|  [
|    "("
|    ")"
|  ] @type)
|
|(type/list
|  [
|    "["
|    "]"
|  ] @type)
|
|(type/star) @type
|
|(constructor) @constructor
|
|((constructor) @boolean
|  (#any-of? @boolean "True" "False"))
|
|((variable) @boolean
|  (#eq? @boolean "otherwise"))
|
|(quoter) @function.call
|
|(quasiquote
|  quoter: [
|    (quoter) @_name
|    (quoter
|      (qualified
|        id: (variable) @_name))
|  ]
|  (#eq? @_name "qq")
|  body: (quasiquote_body) @string)
|
|(quoter
|  [
|    (variable) @function.call
|    (_
|      (module) @title
|      .
|      (variable) @function.call)
|  ])
|
|((variable) @constant.builtin
|  (#any-of? @constant.builtin
|    "error" "undefined" "try" "tryJust" "tryAny" "catch" "catches" "catchJust" "handle" "handleJust"
|    "throw" "throwIO" "throwTo" "throwError" "ioError" "mask" "mask_" "uninterruptibleMask"
|    "uninterruptibleMask_" "bracket" "bracket_" "bracketOnErrorSource" "finally" "fail"
|    "onException" "expectationFailure"))
|
|((variable) @constant.builtin
|  (#any-of? @constant.builtin
|    "trace" "traceId" "traceShow" "traceShowId" "traceWith" "traceShowWith" "traceStack" "traceIO"
|    "traceM" "traceShowM" "traceEvent" "traceEventWith" "traceEventIO" "flushEventLog" "traceMarker"
|    "traceMarkerIO"))
|
|(field_name
|  (variable) @variable.member)
|
|(import_name
|  (name)
|  .
|  (children
|    (variable) @variable.member))
""".trimMargin()
}
