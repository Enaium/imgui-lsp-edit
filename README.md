# lsp-edit

A language-server-driven code editor widget for Kotlin Multiplatform, rendered
with Dear ImGui via [imgui-kmp](https://github.com/Enaium/imgui-kmp) and wired
to language servers through [lsp-kmp](https://github.com/Enaium/lsp-kmp).

The widget is a self-contained `Editor` that you render inside any ImGui
window; an optional `LspEditor` layer binds it to an LSP server
(`didOpen`/`didChange`, diagnostics, hover, completion, go-to-definition,
semantic tokens, folding, inlay hints, code lenses, signature help, rename,
references). A `DiffView` renders side-by-side diffs, and
`DapClient`/`DapSession` speak the Debug Adapter Protocol.

## Features

### `Editor` widget

- ImGui-native rendering: syntax highlighting, line numbers with a solid
  gutter background, fold markers, minimap, selection, undo/redo, find,
  breakpoints, inlay hints, code lenses.
- Built-in right-click context menu (Cut / Copy / Paste / Select All /
  Undo / Redo) with an `onContextMenu` hook so hosts append LSP actions
  (Go to Definition, Find References, Rename…).
- Shift+click and Shift+arrow selection from the current anchor; drag
  selection auto-scrolls vertically and horizontally at the viewport edges.
- Per-editor theme palettes (`palette`, 25 `PaletteIndex` slots), including
  the 46 JetBrains IntelliJ palettes in `JetBrainsThemes`.
- Scroll-follow that never fights manual scrolling (keyboard/edits follow
  the cursor, wheel/scrollbar drags are left alone); opening a document
  never auto-scrolls.
- Callbacks for text edits (`onTextChange`), cursor moves, hover, token
  providers, code-lens clicks and breakpoints so hosts can plug in LSP data.
- `queueTextInput` / `inputText` for host text-input routing, plus
  `isFocusedStrict` (keyboard focus only, excludes hover) for platform
  IME management.

### `MarkdownCode` (markdown with code)

- Renders fenced ` ```lang ``` blocks as read-only, syntax-highlighted code
  (theme palette, no line numbers, no editing, no scrolling) — the
  imgui-kmp Markdown extension has no code hook, so lsp-edit splits the
  input at fences and draws the code blocks itself.
- `` `inline code` `` renders as small bordered chips with a smaller font.

### `JetBrainsThemes`

- 46 editor palettes (29 standard + 17 Material themes from FlatLaf's
  IntelliJ Themes Pack), extracted from each theme's IntelliJ color scheme
  XML + FlatLaf UI colors, switchable at runtime via `editor.palette`.

### `LspClient` (full protocol)

- Lifecycle: `initialize` / `notifyInitialized` / `shutdown` / `exit`,
  document sync (`didOpen` / `didChange` / `didClose`), server notifications
  (`publishDiagnostics`, `showMessage`, `logMessage`).
- Requests: hover, completion, definition, references, prepareRename /
  rename, signatureHelp, documentSymbols (hierarchical + flat), codeLens +
  resolve, workspaceSymbols, semanticTokens/full, inlayHint, foldingRange,
  formatting, typeDefinition, implementation, declaration, documentLinks,
  selectionRange, pull diagnostics.
- Bypasses lsp-kmp's broken `DocumentSymbolResult` array serializer by
  decoding the raw JSON element.

### `LspEditor` (LSP binding)

- Document lifecycle and incremental `didChange` sync.
- `publishDiagnostics` → gutter markers.
- Hover tooltips (markdown, with fenced code blocks via `MarkdownCode`),
  completion popup (detail + description), signature-help popup with the
  active parameter highlighted.
- Go-to-definition / type-definition / implementation, code-lens
  fetch/resolve/click dispatch, rename and references host hooks.
- Full-document semantic tokens, folding ranges, inlay hints — refreshed
  after edits with debounce and stale-response protection.

### Tree-sitter highlighting

- `TreeSitterHighlighter` + `HighlightQueries` (per-language queries from
  zed) live in the `treesitterMain` source set (JVM, macOS, Linux, Windows,
  iOS, Android); the 34 grammar bindings are supplied by the host via
  `tree-sitter-languages-kmp` (see `examples:syntax`).

## Modules

| Module | Description |
| --- | --- |
| `lsp-edit` | The library: `Editor`, `LspEditor`, `LspClient`, `MarkdownCode`, `JetBrainsThemes`, `TreeSitterHighlighter`, `DiffView`, `DapClient`/`DapSession`, transports. |
| `examples:common` | Shared example host (`SdlRendererApp`, sample servers). |
| `examples:editor` | ImGui window hosting `LspEditor` against an in-process demo server (code lens, indent-guide toggle, debug panel). |
| `examples:diff` | Side-by-side `DiffView` of two Kotlin samples. |
| `examples:kotlinlsp` | Real-LSP example: connects to `kotlin-lsp` over stdio. |
| `examples:syntax` | Tree-sitter syntax showcase: 34 grammars + JetBrains theme switcher. |

## Build & test

```bash
./gradlew build                       # compile all targets
./gradlew :lsp-edit:jvmTest           # run the JVM test suite
./gradlew :examples:syntax:jvmTest    # 34-language tree-sitter highlight tests
```

Targets: JVM, macOS, iOS, tvOS, watchOS, Linux, Windows (mingw), Android.

## Examples

JVM (macOS adds `-XstartOnFirstThread` automatically):

```bash
./gradlew :examples:editor:jvmRun
./gradlew :examples:diff:jvmRun
./gradlew :examples:syntax:jvmRun
```

Real Kotlin LSP (requires `kotlin-lsp` on `PATH`; `--stdio` is mandatory —
kotlin-language-server defaults to socket mode):

```bash
./gradlew :examples:kotlinlsp:jvmRun --args="--file /path/to/File.kt"
```

Native targets read `IMGUI_KMP_FRAMES=N` (or `--frames N` on the JVM) to exit
after N frames for headless CI. Every example with native targets (`editor`,
`diff`, `syntax`) links an executable per platform:

```bash
./gradlew :examples:syntax:linkDebugExecutableMacosArm64   # also linuxX64,
                                                           # linuxArm64, mingwX64
IMGUI_KMP_FRAMES=3 ./examples/syntax/build/bin/macosArm64/debugExecutable/syntax.kexe
```

`examples:kotlinlsp` is JVM-only by design: it launches the external
`kotlin-lsp` process and talks to it over its stdio streams.

## Usage

```kotlin
// A plain editor widget inside any ImGui window.
val editor = Editor(initialText = code, language = Language.kotlin)
editor.render("##editor", ImVec2(-1f, -1f))

// Bind it to a language server over any MessageTransport.
val lspEditor = LspEditor(editor, client, uri = "file:///Test.kt", languageId = "kotlin")
lspEditor.start(processId = pid)          // initialize + didOpen
lspEditor.drain()                         // call every frame
lspEditor.editor.inputText(text)          // route host text input to the editor
```

Markdown with fenced code blocks (e.g. LSP hover content):

```kotlin
MarkdownCode.render(markdownConfig, hoverText, language = editor.language, palette = editor.palette)
```

Switch editor themes:

```kotlin
editor.palette = JetBrainsThemes.all[index].second
```

Tree-sitter highlighting (host supplies the grammar bindings):

```kotlin
val highlighter = TreeSitterHighlighter(spec.language(), spec.query)
highlighter.setText(code)
editor.tokenProvider = { line -> highlighter.spansForLine(line) }
```

See the `examples/` modules for complete wiring, including how text input is
routed from the host event loop.

## License

MIT — see [LICENSE](LICENSE).
