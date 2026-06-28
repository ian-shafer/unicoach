# RFC 83: Compact display of entity id columns in the admin website

## Executive Summary

Entity id columns in the admin website render full-length values, and UUID ids
(36 characters) consume disproportionate horizontal space in list rows, the
detail field table, and edge-panel tables. This RFC compacts UUID id cells to an
ellipsis plus their last N characters while keeping the full value reachable.

The compaction targets UUIDs specifically because the codebase has two id
shapes: most entities use `uuidv7()` UUID primary keys, but `observations` and
`extraction_runs` use `BIGINT GENERATED ALWAYS AS IDENTITY` keys. A UUIDv7
encodes a millisecond timestamp in its high bits, so rows created close together
share an identical leading prefix and the distinguishing entropy is in the tail;
compaction therefore keeps the tail. A sequential `BIGINT` is short and fully
meaningful, so it must not be compacted at all. A length- or shape-based
heuristic would have to re-derive this distinction at render time on every cell;
instead a new `FieldType.UUID` makes the classification explicit at the column
declaration, and the render layer compacts iff the column is typed `UUID`.

The compacted cell carries the full value two ways: a hover `title` (reusing the
datetime hover-title convention in `renderValue`) and a per-cell click-to-copy
button backed by the first admin-server client-side script. The change rides the
central `renderCell`/`renderValue` path, so list, detail, and edge-panel cells
compact uniformly with no per-view code. Configuration adds `idTailChars`
(default 8) and the operator-configurable `copyGlyph` to the fail-fast admin
display config.

## Detailed Design

### `FieldType.UUID`

A new enum constant `UUID` is added to `FieldType` in
`admin-server/src/main/kotlin/ed/unicoach/admin/engine/AdminField.kt`. It marks
a column whose string value is a canonical UUID and so should render compacted.
It is orthogonal to `refSlug`: `refSlug` controls the trailing navigation glyph
(`renderRefLink`), `FieldType.UUID` controls value compaction (`renderValue`). A
column may be one, both, or neither — a navigable UUID id sets both; a UUID with
no admin-resource target (e.g. `convoId`) sets `UUID` only; a `BIGINT` id sets
neither.

`FieldType` is consumed by two `when (type)` switches. `renderValue` in
`CellRender.kt` is exhaustive (no `else`) and gains a `UUID` branch (below).
`renderInput` in `FormView.kt` has an `else` fallback and needs no change; UUID
id/FK columns are all `editable = false` and never reach a form.

### Column classification

Every column is classified by the Postgres type of the value it carries (ground
truth: `db/schema/`). UUID-valued columns become `FieldType.UUID`;
`BIGINT`-valued columns stay `FieldType.TEXT` (unchanged). The complete set of
columns flipped to `FieldType.UUID`:

| File                        | Column(s)                                                                                                 | Carrier                                         |
| --------------------------- | --------------------------------------------------------------------------------------------------------- | ----------------------------------------------- |
| `StudentsResource.kt`       | `id`, `userId` AdminFields; edge `Column("ID", refSlug="claim")` (×2: claims panel, student-claims panel) | `students.id`, `users.id`, `claims.id`          |
| `UsersResource.kt`          | `id` AdminField; edge `Column("ID", refSlug="session")`                                                   | `users.id`, `sessions.id`                       |
| `SessionsResource.kt`       | `id`, `userId` AdminFields                                                                                | `sessions.id`, `users.id`                       |
| `SystemPromptsResource.kt`  | `id` AdminField                                                                                           | `system_prompts.id`                             |
| `ClaimsResource.kt`         | `id`, `studentId`, `supersededById` AdminFields                                                           | `claims.id`, `students.id`                      |
| `ObservationsResource.kt`   | `studentId`, `convoId` AdminFields; edge `Column("ID", refSlug="claim")`                                  | `students.id`, `convos.id`, `claims.id`         |
| `ExtractionRunsResource.kt` | `studentId`, `systemPromptId`, `convoId` AdminFields                                                      | `students.id`, `system_prompts.id`, `convos.id` |

Columns deliberately left `FieldType.TEXT` because their carrier is `BIGINT`:
`observations.id` and `extraction_runs.id` (their own primary-id columns and
every edge `Column("ID", refSlug="observation")` / `refSlug="extraction-run")`
in `ClaimsResource.kt` and `StudentsResource.kt`), and `sourceRequestId` /
`throughRequestId` (carry `convo_requests.id`, `BIGINT`).

`convoId` carries a UUID but has no `refSlug` (the `convos` table is not a
registered admin resource), so it compacts without a navigation glyph — the
typed approach extends compaction to non-navigable UUID columns, which a
`refSlug`-gated approach could not reach.

The embedded student panel (`StudentsResource.buildStudentPanel`) copies each
field's `type` and `refSlug` into its `EdgePanel.LabeledCell`, so flipping the
`StudentsResource` AdminFields to `UUID` propagates to the student panel
embedded in the user detail page with no separate edit.

### Compaction and copy: `renderValue` UUID branch

`renderValue` in `CellRender.kt` gains
`FieldType.UUID -> renderIdValue(value,
display)`. `renderValue`'s existing
leading `if (value.isBlank()) return` guard runs first, so a blank id renders
nothing (no span, no button, no glyph) — a NULL FK cell stays empty.

`renderIdValue` (new `private fun FlowContent`) renders, in order:

1. A `span` carrying `title = value` (the full UUID). Its text is `…` (U+2026)
   followed by `value.takeLast(display.idTailChars)` when
   `value.length >
   display.idTailChars`; otherwise the verbatim `value` with
   no ellipsis prefix (defensive — a value no longer than the tail width is
   shown whole rather than prefixed with a misleading ellipsis). The leading
   prefix is never rendered.
2. A copy `button`, kept deliberately minimal: a single `button` element with no
   child elements, carrying only `type="button"`, CSS class `id-copy`, and the
   `data-full = value` attribute, with the configured `display.copyGlyph` as its
   sole text node. A list view emits one such button per UUID cell across every
   row, so the per-button footprint is bounded to exactly these attributes — no
   nested spans, no SVG, no inline event handler, no per-element id, and no
   JS-side state object (the single delegated listener in `SCRIPT` carries all
   behavior; see below). `type="button"` is required so the control never
   submits an enclosing form (no id cell renders inside a form today, but the
   embedded-panel field table abuts one).

`renderCell` is unchanged: it calls `renderValue` then `renderRefLink`, so the
existing id-link glyph still renders after the compacted value and copy button
when `refSlug` names a supported resource. The full value remains in three
places in the DOM — the `title`, the `data-full` attribute, and the glyph `href`
(`/{refSlug}/{value}`, built from the full value by `renderRefLink`).

The copy glyph is operator-configurable via `display.copyGlyph`, mirroring the
existing `idLinkGlyph` / `boolTrueGlyph` / `boolFalseGlyph` glyph settings.

### Render context: `AdminDisplay.idTailChars`

`AdminDisplay` (`CellRender.kt`) gains `idTailChars: Int` and
`copyGlyph: String`. `DisplayConfig.toAdminDisplay` carries both through
alongside the existing fields. The render layer reads tail width from
`display.idTailChars` and the copy-button glyph from `display.copyGlyph`.

### Client-side copy script: `Layout.kt`

`Layout.kt` gains a `SCRIPT` string constant, emitted via
`script { unsafe {
+SCRIPT } }` as the last child of `body` in `adminPage`,
mirroring the existing inline `style { unsafe { +STYLES } }` pattern. This is
the first admin-server client-side script; an inline script is permitted because
admin-server serves no static-asset route and sets no Content-Security-Policy.
(Caveat for a future RFC: if a CSP is added, this must move to a nonce'd or
externally-served script.)

`SCRIPT` registers one delegated `click` listener on `document`: a click whose
`target.closest('.id-copy')` is non-null writes that element's `data-full` to
the clipboard via `navigator.clipboard.writeText`. The brief "copied" state is a
single CSS-class toggle on the clicked button that a lone `setTimeout` removes
after a short interval — no per-button state is stored — so the affordance stays
stateless in markup and cheap at scale. The handler guards on
`navigator.clipboard` being present and degrades to a no-op when it is absent
(the clipboard API is undefined on a non-secure origin — admin-server binds
`127.0.0.1`, a secure context, but may be reverse-proxied over plain HTTP); the
hover `title` remains the fallback path to the full value in that case.

`STYLES` in `Layout.kt` gains a small rule set for the `.id-copy` button — an
unobtrusive inline control (no border or background by default, sized to the
glyph) plus its transient copied state — kept minimal to match the lightweight
button. The script is emitted on every admin page including `nav = false` pages
(login, errors); it is an idempotent passive listener and harmless where no id
cell exists.

### Configuration

`DisplayConfig` (`AdminConfig.kt`) gains `idTailChars: Int` and
`copyGlyph: String`. `AdminConfig.from` parses both inside the existing
`runCatching` fail-fast block: `idTailChars` via `display.getInt("idTailChars")`
validated `require(idTailChars > 0) { ... }` (mirroring the `ZoneId.of`
fail-fast on `timezone`), and `copyGlyph` via `display.getString("copyGlyph")`
(mirroring the existing glyph settings, which carry no further validation). A
missing key, a non-integer `idTailChars`, or a non-positive `idTailChars` fails
startup.

`admin-server.conf` gains, in the `admin.display` block:

```
idTailChars = 8
idTailChars = ${?ADMIN_ID_TAIL_CHARS}
copyGlyph   = "⧉"
copyGlyph   = ${?ADMIN_COPY_GLYPH}
```

### Error Handling / Edge Cases

- Blank id value: `renderValue`'s existing early return — no markup.
- `value.length <= idTailChars`: rendered whole, no ellipsis (guard in
  `renderIdValue`).
- `idTailChars` ≥ value length (e.g. mis-set large): `takeLast` returns the
  whole string and the length guard suppresses the ellipsis, so the cell shows
  the full value rather than `…` + full value.
- Non-positive `idTailChars`: rejected at startup by `require`.
- `navigator.clipboard` absent (non-secure origin): copy handler no-ops; `title`
  hover remains the fallback.
- `BIGINT` id (`observations`, `extraction_runs`): `FieldType.TEXT`, rendered
  raw exactly as today — no compaction, no copy button.

### Dependencies

None new. `kotlinx.html` already provides `button`, `span`, and `script`;
`attributes[...]` sets `data-full` and `type`. Typesafe Config already provides
`getInt` and `getString`.

## Tests

### `CellRenderTest.kt` (render-layer unit tests)

The shared `AdminDisplay` fixture gains `idTailChars = 8` and a known
`copyGlyph`.

- `uuid value renders ellipsis plus the last idTailChars characters`: render a
  known 36-char UUID as `FieldType.UUID`; assert the output contains `…`
  followed by the UUID's last 8 characters and does not contain the leading
  prefix as visible text.
- `uuid value carries the full value in a hover title`: assert
  `title="<full-uuid>"` is present.
- `uuid value emits a copy button carrying the full value`: assert the output
  contains a `button` with class `id-copy`, `type="button"`, and
  `data-full="<full-uuid>"`.
- `copy button text is the configured copyGlyph`: render with a fixture copy
  setting a distinct `copyGlyph`; assert that glyph is the button's text.
- `uuid cell with a supported refSlug still renders the link glyph after the
  compacted value`:
  render via `renderCell` with `refSlug` supported; assert both the compacted
  value and the glyph `href="/<slug>/<full-uuid>"` are present.
- `uuid cell with an unsupported refSlug renders no glyph but still compacts and
  copies`:
  assert no `<a` and the presence of the compacted span and copy button.
- `blank uuid value renders nothing`: assert the cell is empty (no span, no
  button).
- `uuid value no longer than idTailChars renders whole without an ellipsis`:
  render a value of length ≤ 8 as `FieldType.UUID`; assert the verbatim value
  and no `…`.
- `bigint id value of FieldType.TEXT renders raw with no compaction or copy
  button`:
  render a short integer as `FieldType.TEXT` with a `refSlug`; assert the raw
  value, the glyph, and no `id-copy` button.
- `idTailChars from the display context controls the tail width`: render with a
  fixture copy where `idTailChars = 12`; assert the last 12 characters are
  shown.

### `AdminConfigTest.kt` (config fail-fast tests)

- `parses admin display defaults` (existing): extend to assert
  `idTailChars == 8` and `copyGlyph == "⧉"`.
- `idTailChars override is parsed`: parse a config setting `idTailChars = 4`;
  assert `display.idTailChars == 4`.
- `copyGlyph override is parsed`: parse a config setting a distinct `copyGlyph`;
  assert `display.copyGlyph` equals it.
- `non-positive idTailChars fails fast`: parse a config with `idTailChars = 0`;
  assert `AdminConfig.from(...).isFailure`.
- `missing idTailChars fails fast`: parse a `display` block omitting
  `idTailChars`; assert `isFailure`.

### Regression (no change expected; verified by running the suite)

The existing `ExtractionRunsResourceTest` "convoId cell … no link" and
`UsersResourceTest` "blank User ID cell … no link" regex assertions
(`<td>[^<]*<a`) continue to pass: the no-refSlug and blank paths emit no `<a`.
All `contains("/{slug}/{id}")` and `contains("🔗")` assertions continue to pass
because `renderRefLink` builds the glyph `href` from the full value.

## Implementation Plan

1. **Add `FieldType.UUID`.** Add the `UUID` constant to the `FieldType` enum in
   `AdminField.kt`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin` fails on the
     non-exhaustive `when` in `CellRender.kt` (proving the enum is wired and the
     compiler enforces the new branch). Proceed to step 2.

2. **Add `idTailChars` to config.** Add `idTailChars: Int` to `DisplayConfig`;
   parse it in `AdminConfig.from` via `display.getInt("idTailChars")` with
   `require(idTailChars > 0)`. Add `idTailChars = 8` plus the
   `${?ADMIN_ID_TAIL_CHARS}` override to the `admin.display` block of
   `admin-server.conf`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`.

3. **Thread `idTailChars` into the render context.** Add `idTailChars: Int` to
   `AdminDisplay` and carry it in `DisplayConfig.toAdminDisplay`
   (`CellRender.kt`).
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin` (the
     `Application.kt` `toAdminDisplay` call site needs no change).

4. **Implement the UUID render branch.** Add `renderIdValue` and the
   `FieldType.UUID -> renderIdValue(value, display)` branch to `renderValue` in
   `CellRender.kt`: compacted `span` (`title` = full value; `…` + `takeLast`
   tail, with the length guard) followed by the `type="button"` `id-copy` button
   carrying `data-full`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`.

5. **Add the copy script and styles.** Add the `SCRIPT` constant to `Layout.kt`,
   emit it via `script { unsafe { +SCRIPT } }` as the final child of `body` in
   `adminPage`, and add `.id-copy` (and copied-state) rules to `STYLES`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`.

6. **Classify columns.** Flip every column in the Detailed Design classification
   table from `FieldType.TEXT` to `FieldType.UUID` across the seven resource
   files. Leave `BIGINT`-carrying columns as `FieldType.TEXT`.
   - Verify: `nix develop -c ./gradlew :admin-server:compileKotlin`; then
     `nix develop -c grep -rn 'refSlug = "observation"\|refSlug = "extraction-run"' admin-server/src/main/kotlin/ed/unicoach/admin/resources/`
     shows those columns retain no `FieldType.UUID`.

7. **Add render-layer and config tests.** Add the `CellRenderTest.kt` and
   `AdminConfigTest.kt` cases from the Tests section (extend the
   `AdminDisplay`/defaults fixtures with `idTailChars`).
   - Verify:
     `nix develop -c bin/test admin-server -f --tests "ed.unicoach.admin.render.CellRenderTest"`
     and `... --tests "ed.unicoach.admin.AdminConfigTest"` — confirm "N
     executed", not all-cached.

8. **Run the full admin-server suite.** Confirm no resource-test regression.
   - Verify: `nix develop -c bin/test admin-server -f` — all green, "N
     executed".

9. **Lint.**
   - Verify: `nix develop -c bin/test check` passes (ktlint + tests).

## Files Modified

- `admin-server/src/main/kotlin/ed/unicoach/admin/engine/AdminField.kt` — add
  `FieldType.UUID`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/AdminConfig.kt` — add
  `idTailChars` to `DisplayConfig`; parse and validate in `AdminConfig.from`.
- `admin-server/src/main/resources/admin-server.conf` — add `idTailChars`
  (default + `ADMIN_ID_TAIL_CHARS` override).
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/CellRender.kt` — add
  `AdminDisplay.idTailChars`, carry it in `toAdminDisplay`, add `renderIdValue`
  and the `FieldType.UUID` branch in `renderValue`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/render/Layout.kt` — add the
  `SCRIPT` constant and its `script { unsafe { ... } }` emission in `adminPage`;
  add `.id-copy` rules to `STYLES`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/StudentsResource.kt`
  — flip `id`, `userId`, and the `refSlug="claim"` edge columns to
  `FieldType.UUID`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/UsersResource.kt` —
  flip `id` and the `refSlug="session"` edge column to `FieldType.UUID`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/SessionsResource.kt`
  — flip `id`, `userId` to `FieldType.UUID`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/SystemPromptsResource.kt`
  — flip `id` to `FieldType.UUID`.
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ClaimsResource.kt` —
  flip `id`, `studentId`, `supersededById` to `FieldType.UUID` (leave the
  `refSlug="observation"` edge column `TEXT`).
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ObservationsResource.kt`
  — flip `studentId`, `convoId`, and the `refSlug="claim"` edge column to
  `FieldType.UUID` (leave `id` and `sourceRequestId` `TEXT`).
- `admin-server/src/main/kotlin/ed/unicoach/admin/resources/ExtractionRunsResource.kt`
  — flip `studentId`, `systemPromptId`, `convoId` to `FieldType.UUID` (leave
  `id` and `throughRequestId` `TEXT`).
- `admin-server/src/test/kotlin/ed/unicoach/admin/render/CellRenderTest.kt` —
  add `idTailChars` to the fixture and the UUID-render test cases.
- `admin-server/src/test/kotlin/ed/unicoach/admin/AdminConfigTest.kt` — extend
  defaults assertion and add `idTailChars` override/fail-fast cases.
