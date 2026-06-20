# SPEC.md — `public-web/src/main/kotlin/ed/unicoach/web/render`

## I. Overview

The server-side HTML rendering layer of `public-web`. It produces every page the
site serves — the brand/home page, the legal pages (Terms of Service, Privacy
Policy), and the branded 404/503 error pages — as `kotlinx.html` markup. A
single shared `siteLayout` skeleton supplies the common chrome (head linking
`/site.css`, top-level header, footer); each page renderer authors only a body
that is injected into the layout's content slot, so every page inherits
identical chrome. The layer holds no domain logic, reads no database, and
mutates no state; every page body is authored in the DSL, never from a static
HTML file.

---

## II. Behavioral Contracts

Every page renderer is an `ApplicationCall` extension that writes one HTML
response via Ktor's `respondHtml`; the layout/chrome functions are
`kotlinx.html` builder extensions that emit nodes. None performs DB or network
access. Calling a renderer twice writes the markup twice (no idempotency concern
beyond that).

### `HTML.siteLayout(pageTitle: String, content: MAIN.() -> Unit)` — [`Layout.kt`](./Layout.kt)

- **Behavior**: Emits the page `<head>` — charset/viewport meta, a `<title>` of
  the form `unicoach — <pageTitle>`, and a `<link>` to the static stylesheet
  `/site.css` — and the `<body>`, which wraps the supplied `content` body
  between `siteHeader()` and `siteFooter()` inside a `<main>`. Every page (home,
  legal, error) renders through this one layout, so all share the same chrome
  and link the same stylesheet.
- **Side effects**: HTML emission only.

### `FlowContent.siteHeader()` — [`Layout.kt`](./Layout.kt)

- **Behavior**: Emits the shared top header chrome: the `unicoach` brand
  wordmark linking to `/`, and a nav with Home / Terms / Privacy links.
- **Side effects**: HTML emission only.

### `FlowContent.siteFooter()` — [`Layout.kt`](./Layout.kt)

- **Behavior**: Emits the shared footer chrome: a `© unicoach` mark and links to
  the Terms of Service and Privacy Policy pages.
- **Side effects**: HTML emission only.

### `ApplicationCall.respondHomePage()` — [`HomePage.kt`](./HomePage.kt)

- **Behavior**: Writes the brand/awareness landing page through `siteLayout`.
  The body is a hero section carrying the heading marker `Your college coach`
  and end-to-end positioning copy (guiding high-school students through the
  entire path to college — exploring schools, choosing the best fit,
  standardized testing, application strategy, the applications, and college
  costs), reflecting the broad framing in the top-level `PRODUCT.md` without
  singling out one stage, plus a link to the Terms page.
- **Side effects**: Writes one `200` `text/html` response.

### `ApplicationCall.respondTermsPage()` — [`TermsPage.kt`](./TermsPage.kt)

- **Behavior**: Writes the Terms of Service page through `siteLayout`. The body
  is a legal section with the heading marker `Terms of Service` and placeholder
  boilerplate (use of the service, acceptable use, changes to the terms). The
  copy is static but renders dynamically through the layout, not from a static
  HTML file, so it inherits the shared chrome.
- **Side effects**: Writes one `200` `text/html` response.

### `ApplicationCall.respondPrivacyPage()` — [`PrivacyPage.kt`](./PrivacyPage.kt)

- **Behavior**: Writes the Privacy Policy page through `siteLayout`. The body is
  a legal section with the heading marker `Privacy Policy` and placeholder
  boilerplate (information collected, how it is used, the reader's choices).
  Static copy rendered dynamically through the layout, inheriting the shared
  chrome.
- **Side effects**: Writes one `200` `text/html` response.

### `ApplicationCall.respondNotFoundPage()` / `respondServiceUnavailablePage()` — [`ErrorPages.kt`](./ErrorPages.kt)

- **Behavior**: Branded error pages rendered through `siteLayout`, so they carry
  the same header/footer chrome as every other page — never a default Ktor body
  or a raw stack trace. `respondNotFoundPage` writes a `404` page (heading
  marker `404 Not Found`); `respondServiceUnavailablePage` writes a `503` page
  (heading marker `503 Service Unavailable`). Both delegate to a shared private
  `respondErrorPage(status, heading, detail)` helper that emits an error section
  at the given status.
- **Side effects**: Writes one HTML response each, at the `404` / `503` status
  respectively.
- **Note**: These are the handlers the routing layer's `StatusPages` install
  invokes — `respondNotFoundPage` from the `NotFound` status handler,
  `respondServiceUnavailablePage` from the `exception<Throwable>` catch-all (see
  the sibling [`Routing.kt`](../Routing.kt)).

---

## III. Infrastructure & Environment

- **`io.ktor:ktor-server-html-builder`** — supplies
  `ApplicationCall.respondHtml`, used by every page renderer to write
  `kotlinx.html` documents as HTTP responses.
- **`kotlinx.html`** — the HTML DSL every layout, chrome, and page body targets.
  It arrives transitively via `ktor-server-html-builder`; there is no separate
  catalog entry.
- The shared stylesheet `/site.css` is served by the static asset mount in the
  sibling routing layer, not by this package; renderers link it by path.
- No environment variables or config keys are read by this directory.

---

## IV. History

- [x] [RFC-61: Public Web Module (Dynamic HTML via Shared Layout)](../../../../../../../../rfc/61-static-marketing-site.md)
