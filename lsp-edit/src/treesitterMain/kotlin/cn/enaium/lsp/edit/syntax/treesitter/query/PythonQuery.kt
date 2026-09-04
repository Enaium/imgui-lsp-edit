// GENERATED FILE — do not edit by hand. Regenerate with the
// scripts/gen-queries.py logic (node-types.json + curated patterns).
package cn.enaium.lsp.edit.syntax.treesitter.query

/** Highlight query for Python (from zed highlights.scm). */
object PythonQuery {
    val query: String = """
|(identifier) @variable
|
|(attribute
|  attribute: (identifier) @property)
|
|((identifier) @type.class
|  (#match? @type.class "^_*[A-Z][A-Za-z0-9_]*${'$'}"))
|
|((identifier) @constant
|  (#match? @constant "^_*[A-Z][A-Z0-9_]*${'$'}"))
|
|(type
|  (identifier) @type)
|
|(generic_type
|  (identifier) @type)
|
|(comment) @comment
|
|(string) @string
|
|(escape_sequence) @string.escape
|
|(type_alias_statement
|  "type" @keyword)
|
|(type
|  (tuple
|    (identifier) @type))
|
|(type
|  (string) @type)
|
|(call
|  function: (attribute
|    attribute: (identifier) @function.method.call))
|
|(call
|  function: (identifier) @function.call)
|
|(decorator
|  "@" @punctuation.special)
|
|(decorator
|  "@" @punctuation.special
|  [
|    (identifier) @function.decorator
|    (attribute
|      attribute: (identifier) @function.decorator)
|    (call
|      function: (identifier) @function.decorator.call)
|    (call
|      (attribute
|        attribute: (identifier) @function.decorator.call))
|  ])
|
|(function_definition
|  name: (identifier) @function.definition)
|
|((call
|  function: (identifier) @_isinstance
|  arguments: (argument_list
|    (_)
|    (identifier) @type))
|  (#eq? @_isinstance "isinstance"))
|
|((call
|  function: (identifier) @_issubclass
|  arguments: (argument_list
|    (identifier) @type
|    (identifier) @type))
|  (#eq? @_issubclass "issubclass"))
|
|(function_definition
|  parameters: (parameters
|    [
|      (identifier) @variable.parameter ; Simple parameters
|      (typed_parameter
|        (identifier) @variable.parameter) ; Typed parameters
|      (default_parameter
|        name: (identifier) @variable.parameter) ; Default parameters
|      (typed_default_parameter
|        name: (identifier) @variable.parameter) ; Typed default parameters
|      (list_splat_pattern
|        (identifier) @variable.parameter) ; List splat parameters (*args)
|      (dictionary_splat_pattern
|        (identifier) @variable.parameter) ; Dictionary splat parameters (**kwargs)
|      (typed_parameter
|        (list_splat_pattern
|          (identifier) @variable.parameter)) ; Typed list splat parameters
|      (typed_parameter
|        (dictionary_splat_pattern
|          (identifier) @variable.parameter)) ; Typed dictionary splat parameters
|    ]))
|
|(call
|  arguments: (argument_list
|    (keyword_argument
|      name: (identifier) @function.kwargs)))
|
|(class_definition
|  name: (identifier) @type.class.definition)
|
|(class_definition
|  superclasses: (argument_list
|    (identifier) @type.class.inheritance))
|
|(call
|  function: (identifier) @type.class.call
|  (#match? @type.class.call "^_*[A-Z][A-Za-z0-9_]*${'$'}"))
|
|((call
|  function: (identifier) @function.builtin)
|  (#any-of? @function.builtin
|    "abs" "aiter" "all" "anext" "any" "ascii" "bin" "bool" "breakpoint" "bytearray" "bytes"
|    "callable" "chr" "classmethod" "compile" "complex" "delattr" "dict" "dir" "divmod" "enumerate"
|    "eval" "exec" "filter" "float" "format" "frozenset" "getattr" "globals" "hasattr" "hash" "help"
|    "hex" "id" "input" "int" "isinstance" "issubclass" "iter" "len" "list" "locals" "map" "max"
|    "memoryview" "min" "next" "object" "oct" "open" "ord" "pow" "print" "property" "range" "repr"
|    "reversed" "round" "set" "setattr" "sentinel" "slice" "sorted" "staticmethod" "str" "sum"
|    "super" "tuple" "type" "vars" "zip" "__import__"))
|
|[
|  (true)
|  (false)
|] @boolean
|
|[
|  (none)
|  (ellipsis)
|] @constant.builtin
|
|[
|  (integer)
|  (float)
|] @number
|
|[
|  (parameters
|    (identifier) @variable.special)
|  (attribute
|    (identifier) @variable.special)
|  (#any-of? @variable.special "self" "cls")
|]
|
|[
|  "."
|  ","
|  ":"
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
|(interpolation
|  "{" @punctuation.special
|  "}" @punctuation.special) @embedded
|
|([
|  (expression_statement
|    (assignment))
|  (type_alias_statement)
|]
|  .
|  (expression_statement
|    (string) @string.doc)+)
|
|(module
|  .
|  (expression_statement
|    (string) @string.doc)+)
|
|(class_definition
|  body: (block
|    .
|    (expression_statement
|      (string) @string.doc)+))
|
|(function_definition
|  "async"?
|  "def"
|  name: (_)
|  (parameters)?
|  body: (block
|    .
|    (expression_statement
|      (string) @string.doc)+))
|
|(class_definition
|  body: (block
|    .
|    (comment) @comment*
|    .
|    (expression_statement
|      (string) @string.doc)+))
|
|(module
|  .
|  (comment) @comment*
|  .
|  (expression_statement
|    (string) @string.doc)+)
|
|(class_definition
|  body: (block
|    (expression_statement
|      (assignment))
|    .
|    (expression_statement
|      (string) @string.doc)+))
|
|(class_definition
|  body: (block
|    (function_definition
|      name: (identifier) @function.method.constructor
|      (#eq? @function.method.constructor "__init__")
|      body: (block
|        (expression_statement
|          (assignment))
|        .
|        (expression_statement
|          (string) @string.doc)+))))
|
|[
|  "-"
|  "-="
|  "!="
|  "*"
|  "**"
|  "**="
|  "*="
|  "/"
|  "//"
|  "//="
|  "/="
|  "&"
|  "%"
|  "%="
|  "@"
|  "^"
|  "+"
|  "->"
|  "+="
|  "<"
|  "<<"
|  "<="
|  "<>"
|  "="
|  ":="
|  "=="
|  ">"
|  ">="
|  ">>"
|  "|"
|  "~"
|  "&="
|  "<<="
|  ">>="
|  "@="
|  "^="
|  "|="
|] @operator
|
|[
|  "and"
|  "in"
|  "is"
|  "not"
|  "or"
|  "is not"
|  "not in"
|] @keyword.operator
|
|[
|  "as"
|  "assert"
|  "async"
|  "await"
|  "break"
|  "class"
|  "continue"
|  "def"
|  "del"
|  "elif"
|  "else"
|  "except"
|  "exec"
|  "finally"
|  "for"
|  "from"
|  "global"
|  "if"
|  "import"
|  "lambda"
|  "nonlocal"
|  "pass"
|  "print"
|  "raise"
|  "return"
|  "try"
|  "while"
|  "with"
|  "yield"
|  "match"
|  "case"
|] @keyword
|
|[
|  "async"
|  "def"
|  "class"
|  "lambda"
|] @keyword.definition
|
|(decorator
|  (identifier) @attribute.builtin
|  (#any-of? @attribute.builtin "classmethod" "staticmethod" "property"))
|
|(attribute
|  attribute: (identifier) @attribute.special
|  (#any-of? @attribute.special
|    "__all__" "__annotations__" "__bases__" "__builtins__" "__class__" "__closure__" "__code__"
|    "__debug__" "__defaults__" "__dict__" "__doc__" "__file__" "__func__" "__globals__"
|    "__kwdefaults__" "__match_args__" "__members__" "__metaclass__" "__methods__" "__module__"
|    "__mro__" "__mro_entries__" "__name__" "__qualname__" "__post_init__" "__self__" "__signature__"
|    "__slots__" "__subclasses__" "__version__" "__weakref__" "__wrapped__" "__classcell__"
|    "__spec__" "__path__" "__package__" "__future__" "__traceback__"))
|
|[
|  (call
|    function: (identifier) @type.builtin)
|  (type
|    (identifier) @type.builtin)
|  (generic_type
|    (identifier) @type.builtin)
|  (type
|    (binary_operator
|      left: (identifier) @type.builtin))
|  (#any-of? @type.builtin
|    "bool" "bytearray" "bytes" "complex" "dict" "float" "frozenset" "frozendict" "int" "list"
|    "memoryview" "object" "range" "set" "slice" "str" "tuple")
|]
|
|((identifier) @type.class.builtin
|  (#any-of? @type.class.builtin
|    "BaseException" "Exception" "ArithmeticError" "BufferError" "LookupError" "AssertionError"
|    "AttributeError" "EOFError" "FloatingPointError" "GeneratorExit" "ImportError"
|    "ModuleNotFoundError" "IndexError" "KeyError" "KeyboardInterrupt" "MemoryError" "NameError"
|    "NotImplementedError" "OSError" "OverflowError" "RecursionError" "ReferenceError" "RuntimeError"
|    "StopIteration" "StopAsyncIteration" "SyntaxError" "IndentationError" "TabError" "SystemError"
|    "SystemExit" "TypeError" "UnboundLocalError" "UnicodeError" "UnicodeEncodeError"
|    "UnicodeDecodeError" "UnicodeTranslateError" "ValueError" "ZeroDivisionError" "EnvironmentError"
|    "IOError" "WindowsError" "BlockingIOError" "ChildProcessError" "ConnectionError"
|    "BrokenPipeError" "ConnectionAbortedError" "ConnectionRefusedError" "ConnectionResetError"
|    "FileExistsError" "FileNotFoundError" "InterruptedError" "IsADirectoryError"
|    "NotADirectoryError" "PermissionError" "ProcessLookupError" "TimeoutError" "ExceptionGroup"
|    "BaseExceptionGroup"
|    "Warning" "UserWarning" "DeprecationWarning" "PendingDeprecationWarning" "SyntaxWarning"
|    "RuntimeWarning" "FutureWarning" "ImportWarning" "UnicodeWarning" "EncodingWarning"
|    "BytesWarning" "ResourceWarning"))
""".trimMargin()
}
