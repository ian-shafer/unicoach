# INVARIANTS — public-web/.../web/render

The server-side HTML rendering layer of `public-web`: every page body authored
in the `kotlinx.html` DSL and injected into one shared layout.

## Invariants

### Every page renders through the one shared `siteLayout`

**Rule:** Every page — home, legal, and error — MUST render its body through
`siteLayout`. A renderer MUST NOT call `respondHtml` with its own standalone
document, and no page may be authored as a static HTML-fragment file or via a
raw-HTML injection seam.

**Why:** `siteLayout` is the single seam that supplies the shared header/footer
chrome, nav, and the `/site.css` link, and is the documented integration point a
future email-verification flow plugs its result pages into. A page that bypasses
the layout loses the shared chrome and stylesheet and erodes the single-seam
design the module is built on.

### Brand copy reflects `PRODUCT.md`'s end-to-end positioning

**Rule:** `HomePage` copy, and any tagline or header/footer wordmark chrome,
MUST reflect the broad end-to-end positioning in the top-level `PRODUCT.md` — a
college coach guiding students through the whole path to college — and MUST NOT
re-position Unicoach as specializing in any single stage. (The legal pages are
exempt; they carry generic boilerplate.)

**Why:** `PRODUCT.md` is the single canonical source of truth for what Unicoach
is, and the top-level `CLAUDE.md` `## Product` section binds all public-facing
brand copy to it. The home page is the brand-awareness surface; narrowing its
copy to one stage drifts the public positioning away from its canonical source.

## History

- [x] [RFC-61: Public Web Module (Dynamic HTML via Shared Layout)](../../../../../../../../rfc/61-static-marketing-site.md)
