# lsp-edit

A language-server-driven code editor widget for Kotlin Multiplatform, rendered
with Dear ImGui via [imgui-kmp](https://github.com/Enaium/imgui-kmp) and wired
to language servers through [lsp-kmp](https://github.com/Enaium/lsp-kmp).

The widget is a self-contained `Editor` that you render inside any ImGui
window; an optional `LspEditor` layer binds it to an LSP server
(`didOpen`/`didChange`, diagnostics, hover, completion, go-to-definition,
semantic tokens, folding, inlay hints). A `DiffView` renders side-by-side
diffs, and `DapClient`/`DapSession` speak the Debug Adapter Protocol.

## Features

`Editor` widget:

- ImGui-native rendering: syntax highlighting, line numbers, fold markers,
  minimap, selection, undo/redo, find, breakpoints, inlay hints.
- Callbacks for text edits (`onTextChange`), cursor moves, hover, and token
  providers so hosts can plug in LSP data.
- Scroll-follow that never fights manual scrolling (keyboard/edits follow the
  cursor, wheel/scrollbar drags are left alone).

`LspEditor` (LSP binding):

- Document lifecycle and incremental `didChange` sync.
- `publishDiagnostics` → gutter markers.
- Hover tooltips (markdown), completion popup (detail + description).
- Go-to-definition (F12 / toolbar button).
- Full-document semantic tokens, folding ranges, inlay hints — refreshed
  after edits with debounce and stale-response protection.

## Modules

| Module | Description |
| --- | --- |
| `lsp-edit` | The library: `Editor`, `LspEditor`, `LspClient`, `DiffView`, `DapClient`/`DapSession`, transports. |
| `examples:common` | Shared example host (`SdlRendererApp`, sample server). |
| `examples:editor` | ImGui window hosting `LspEditor` against an in-process demo server. |
| `examples:diff` | Side-by-side `DiffView` of two Kotlin samples. |
| `examples:kotlinlsp` | Real-LSP example: connects to `kotlin-lsp` over stdio. |

## Build & test

```bash
./gradlew build          # compile all targets
./gradlew :lsp-edit:jvmTest   # run the JVM test suite
```

Targets: JVM, macOS, iOS, tvOS, watchOS, Linux, Windows (mingw), Android.

## Examples

JVM (macOS adds `-XstartOnFirstThread` automatically):

```bash
./gradlew :examples:editor:jvmRun
./gradlew :examples:diff:jvmRun
```

Real Kotlin LSP (requires `kotlin-lsp` on `PATH`; `--stdio` is mandatory —
kotlin-language-server defaults to socket mode):

```bash
./gradlew :examples:kotlinlsp:jvmRun --args="--file /path/to/File.kt"
```

Native targets read `IMGUI_KMP_FRAMES=N` (or `--frames N` on the JVM) to exit
after N frames for headless CI.

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

See the `examples/` modules for complete wiring, including how text input is
routed from the host event loop.

## License

MIT — see [LICENSE](LICENSE).
