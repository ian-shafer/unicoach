# INVARIANTS — admin-server/.../admin/render

The server-side HTML rendering layer: turns resolved resource descriptors and
pre-fetched row data into `kotlinx.html` markup for list tables, detail pages,
edge panels, and error pages.

## Invariants

### Every cell routes through `renderCell`

**Rule:** Every cell value the admin site emits — list, detail, edge, and
embedded-panel cells — MUST go through `renderCell(...)`. No view may emit a
cell's value directly (`+value`, `+cells[field.name]`).

**Why:** `renderCell` is the one place the RFC 79 display conventions live
(timezone formatting, boolean glyphs, entity-reference id-link glyphs, blank
suppression). A view that emits a value directly bypasses all of them — e.g. a
raw UUID with no navigation link instead of a formatted, linked cell — and
degrades silently: no exception, no test failure unless a test asserts the
formatted output.

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
