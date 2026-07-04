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

## History

- [x] [RFC-79: Admin display conventions](../../../../../../../../rfc/79-admin-display-conventions.md)
