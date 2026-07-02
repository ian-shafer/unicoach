# INVARIANTS — admin-server/.../admin/render

The server-side HTML rendering layer: turns resolved resource descriptors and
pre-fetched row data into `kotlinx.html` markup for list tables, detail pages,
edge panels, and error pages.

## Invariants

### Every cell routes through `renderCell`

**Rule:** Every cell value emitted by the admin website (list-table cells,
detail-field-table cells, edge-table cells, and embedded-panel field cells) MUST
route through `renderCell(value, type, refSlug, display)`. No view function MUST
emit a cell's string value directly (e.g. `+value` or `+cells[field.name]`
unmediated).

**Why:** `renderCell` is the single place all four RFC 79 display conventions
live: datetime timezone formatting, boolean glyphs, entity-reference id-link
glyphs, and blank suppression. A view that emits a cell value directly bypasses
all four: an id cell shows a raw UUID with no navigation glyph; a timestamp cell
shows a raw ISO string instead of the configured timezone-formatted date; a
boolean cell shows `"true"`/`"false"` text instead of the configured glyphs. The
result is a silently degraded UI — no exception, no test failure unless a test
specifically asserts the formatted output — making the regression easy to miss.

### Cell value text is never wrapped in a hyperlink

**Rule:** No cell's string value MUST be rendered as or inside an `<a>` element.
The trailing `refSlug` glyph emitted by `renderRefLink` MUST be the sole
hyperlink in a cell.

**Why:** The `isSupported` guard in `renderRefLink` ensures a glyph link only
appears when the target slug is a registered admin resource. A direct
`<a href="...">` around the value text bypasses that guard entirely — producing
a link to a non-existent page for unregistered slugs, and doubling the
navigation target for registered ones. RFC 79 explicitly chose glyph-only
navigation so every id cell's link passes through one code path and one
registration check. Wrapping the value text re-opens an unregistered-slug
navigation path that `renderRefLink` exists to close.

## History

- [x] [RFC-79: Admin display conventions](../../../../../../../../rfc/79-admin-display-conventions.md)
