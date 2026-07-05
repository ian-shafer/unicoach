# RFC 102: Admin JSON cell wrapping and syntax highlighting

## Executive Summary

The admin site's `FieldType.JSON` cells (Request Params, Request Content, and
Response Content on a Request detail page, plus `PeriodicJobsResource`'s
`payload` field) render through `renderJsonValue`
(`admin-web/.../render/CellRender.kt`), which pretty-prints the stored JSON
string and dumps it, unstyled, into a bare `<pre>`. A bare `<pre>` never wraps
(`white-space: pre`), so long values force horizontal scroll, and plain text
carries no visual structure — nested JSON is hard to parse at a glance.

This RFC fixes both problems without adding a dependency. `Layout.kt` gains CSS
that makes `<pre>` wrap to the available width. `renderJsonValue` is rewritten
from a pretty-print-then-dump-as-text approach to a recursive renderer that
walks the already-parsed `JsonElement` and emits `kotlinx.html` spans per token
— keys, strings, numbers, booleans, null, and punctuation — each carrying a CSS
class for color/weight/style. No JavaScript, no client-side highlighting
library, no static-asset route: the admin site is 100% server-rendered today,
and this stays that way. The fix lives entirely in the one function every
`FieldType.JSON` cell already shares, so it applies uniformly site-wide.

## Detailed Design

### Data Models

No schema or domain model changes. JSON fields remain `JsonObject`/`JsonElement`
in the DB models (`ConvoRequest`, `ConvoResponse`) and are serialized to their
minified string form (`.toString()`) before reaching the render layer, exactly
as today — only the render layer's handling of that string changes.

### CSS: wrapping

`Layout.kt` `STYLES` gains one rule targeting the JSON `<pre>`:

```css
pre.json-pretty {
  white-space: pre-wrap; /* wrap at whitespace/newlines instead of never wrapping */
  overflow-wrap: anywhere; /* force-break inside a long unbroken run (e.g. a long string with no spaces) */
  margin: 0; /* kill the default <pre> block margin so it sits flush in the td */
}
```

`th, td` already has no `max-width`/`white-space` override; with the source
`<pre>` now able to wrap, the table column no longer forces its width from an
unbreakable pretty-printed line, and no `table-layout: fixed` is needed.

### CSS: token styling

Six new classes, applied by the renderer below:

```css
.json-key {
  color: #0f766e;
  font-weight: bold;
} /* dark teal */
.json-string {
  color: #15803d;
} /* green */
.json-number {
  color: #1d4ed8;
} /* blue */
.json-bool {
  color: #7e22ce;
  font-weight: bold;
} /* purple */
.json-null {
  color: #627d98;
  font-style: italic;
} /* muted gray, matches id-copy */
.json-punct {
  color: #627d98;
  font-weight: bold;
} /* braces/brackets/colon/comma */
```

### Renderer: recursive JsonElement walk

Replaces the `PRETTY_JSON` (`kotlinx.serialization`
`Json { prettyPrint = true
}`) string round-trip entirely. The renderer walks
the parsed tree directly — no re-lexing of pretty-printed text — building
indentation as raw text nodes interleaved with typed `span`s. Indentation is 2
spaces per nesting level (replacing `prettyPrint`'s 4-space default), trading a
slightly less conventional indent for less horizontal width per level, which
directly serves the no-horizontal-scroll goal on deeply nested values.

A module-level constant `JSON_INDENT_UNIT = "  "` (two spaces) fixes the
per-level indent. Five private `kotlinx.html` `FlowContent` extensions replace
the string-based `renderJsonValue` (mirroring RFC 79's signature-plus-prose form
for this same file):

```kotlin
private fun FlowContent.renderJsonValue(value: String)
private fun FlowContent.renderJsonElement(element: JsonElement, indent: String)
private fun FlowContent.renderJsonPrimitive(value: JsonPrimitive)
private fun FlowContent.renderJsonArray(array: JsonArray, indent: String)
private fun FlowContent.renderJsonObject(obj: JsonObject, indent: String)
```

- `renderJsonValue` parses `value` with `Json.parseToJsonElement`. On a parse
  failure it logs at WARN and emits the raw `value` as text, then returns (no
  `<pre>`, never throws). On success it opens `pre("json-pretty")` and
  dispatches the root element via `renderJsonElement` at empty indent.
- `renderJsonElement` dispatches on the four `JsonElement` subtypes: `JsonNull`
  renders `null` in a `json-null` span; `JsonPrimitive` delegates to
  `renderJsonPrimitive`; `JsonArray` and `JsonObject` delegate to their
  respective renderers, carrying the current `indent` down. Because the root
  parsed element may itself be a primitive or null, a bare scalar cell value
  renders through the same dispatch, not only nested tokens.
- `renderJsonPrimitive` classifies the primitive: a string (`isString`) renders
  in a `json-string` span using `JsonPrimitive.toString()`, which supplies the
  JSON-escaped, quoted form directly (no hand-rolled escaping); the two boolean
  literals render in a `json-bool` span; every other primitive (numbers) renders
  in a `json-number` span.
- `renderJsonArray` emits the `[`/`]` brackets in `json-punct` spans, one
  element per line at `indent + JSON_INDENT_UNIT` with `renderJsonElement`
  recursing at the deeper indent; an empty array renders `[]` inline on one
  line.
- `renderJsonObject` emits the `{`/`}` braces in `json-punct` spans, one
  `"key": value` entry per line: the key in a `json-key` span, the `:` and
  inter-entry `,` in `json-punct` spans, the value via `renderJsonElement` at
  the deeper indent; an empty object renders `{}` inline on one line.

The space after `:` and the newline/indentation between entries are plain text
nodes, not part of any token span.

### Error Handling / Edge Cases

- Blank value → renders nothing (unchanged).
- Unparseable JSON → logged at WARN and surfaced as raw text, matching today's
  defensive fallback (never throws).
- Empty object/array → renders `{}` / `[]` inline on one line, not expanded
  across three lines.
- Deeply nested values grow vertically (a taller `<pre>`), not horizontally —
  this RFC does not add truncation or a collapse/expand toggle; not requested,
  and out of scope.

### Dependencies

None new. `kotlinx.serialization.json` (already a dependency, already imported
in `CellRender.kt`) supplies parsing and the token-level string form.

## Tests

All in `admin-web/src/test/kotlin/ed/unicoach/admin/render/CellRenderTest.kt`,
replacing the current
`JSON value/array/primitive renders pretty-printed
inside pre` cases (their
assertions target the old plain-text-in-pre markup and no longer hold):

- `JSON object renders wrapped inside a json-pretty pre` — asserts
  `<pre class="json-pretty">`.
- `JSON key renders inside a json-key span`.
- `JSON string value renders quoted inside a json-string span`.
- `JSON number renders inside a json-number span`.
- `JSON true renders inside a json-bool span` /
  `JSON false renders inside a
  json-bool span`.
- `JSON null renders inside a json-null span`.
- `top-level JSON primitive renders inside a json-pretty pre` — a bare `"hello"`
  / `42` / `true` / `null` as the entire cell value (not nested) still renders
  correctly wrapped in the `json-pretty` `<pre>`, exercising the root-element
  dispatch to `renderJsonPrimitive`/`json-null`.
- `JSON braces, brackets, colon, and comma render inside json-punct spans`.
- `nested JSON object indents by 2 spaces per level`.
- `empty object renders {} on one line`.
- `empty array renders [] on one line`.
- `JSON array renders each element on its own line`.
- `blank JSON value renders nothing` (unchanged).
- `unparseable JSON renders raw text without throwing, and emits no pre`
  (unchanged fallback behavior; assertion updated for the class-qualified
  `<pre>`).
- `JSON field renders no ref link` (unchanged; exercises `renderCell`).

Verification:
`nix develop -c bin/test admin-web --tests "ed.unicoach.admin.render.CellRenderTest" --force`,
then `nix develop -c bin/test admin-web --force` for full-module regression
(independent runs need `--force`; declared vs. executed counts checked per repo
convention).

No dedicated CSS/layout unit test is added — no existing test in this repo
asserts on `Layout.STYLES` content, and styling elsewhere (e.g. RFC 79's
`bool-true`/`bool-false`/`id-link` classes) isn't unit-tested either, only the
markup's class names are. The wrap fix itself is verified by manually loading a
Request detail page with a long nested JSON value (Implementation Plan step 2).

## Invariants

None. This is a presentation refinement of an existing render path, not a new
durable guarantee — the existing `render/INVARIANTS.md` rule ("every cell value
routes through `renderCell`") already covers the structural risk of a future
view bypassing the shared renderer.

## Implementation Plan

1. **Rewrite JSON rendering in `CellRender.kt`.** Remove `PRETTY_JSON` and the
   string-based `renderJsonValue`. Add the recursive tree-walk renderer:
   `renderJsonValue` (parses, falls back to raw text + WARN log on failure,
   otherwise wraps the tree in `pre("json-pretty")`), `renderJsonElement`,
   `renderJsonPrimitive`, `renderJsonArray`, `renderJsonObject` — emitting
   `json-key` / `json-string` / `json-number` / `json-bool` / `json-null` /
   `json-punct` spans with 2-space-per-level indentation.
   - Verify: `nix develop -c ./gradlew :admin-web:compileKotlin`.
2. **Add CSS.** In `Layout.kt` `STYLES`: the `pre.json-pretty` wrap rule plus
   the six `.json-*` token classes, per Detailed Design.
   - Verify: `nix develop -c ./gradlew :admin-web:compileKotlin`; run admin-web
     locally, open a Request detail page for a request with a long/nested JSON
     value, and confirm no horizontal scroll and visible token coloring.
3. **Rewrite `CellRenderTest.kt`'s JSON cases** per the Tests section above.
   - Verify:
     `nix develop -c bin/test admin-web --tests "ed.unicoach.admin.render.CellRenderTest" --force`.
4. **Full-module regression.**
   - Verify: `nix develop -c bin/test admin-web --force`.

## Files Modified

- `admin-web/src/main/kotlin/ed/unicoach/admin/render/CellRender.kt`
- `admin-web/src/main/kotlin/ed/unicoach/admin/render/Layout.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/render/CellRenderTest.kt`
