# RFC 210 — The web shell

Slice `web/01/the-shell` of brief 0013, _Unicoach on the web_. The responsive
shell every later web page renders into: layout, navigation, the CSS system, and
a content-security policy written before any inline-script habit forms.

## Context

`public-web` today is a marketing site wearing an app's name. `siteLayout` links
one stylesheet, `main` is a fixed `max-width: 760px` column, and the single
`@media (max-width: 640px)` rule in `site.css` exists only to stack the Family
Cost Report table (RFC 155). That is enough for four static pages. It is not
enough for the eleven slices behind this one, which add sign-in, a chat view
with a pinned composer, and three forms.

This slice changes no page's content and adds no route. It changes how every
existing page looks at every width, and it fixes the shape later slices extend.

Binding decisions carried in from brief 0013:

- **D9 — fluid-first responsive; evidence = mechanisms + device matrix.** Fluid
  layout covering 320–1440px continuously, two to three breakpoints. Automated
  tests pin MECHANISMS. The 90% claim is evidenced by a manual device-matrix
  checklist, never by a unit test.
- **D13 — the web calls the service layer in-process.** Nothing in this slice
  calls anything; it is the constraint that no business rule is written here.
- **D19 — the device matrix.** iPhone SE 375×667, Android 412×915, iPad 820×1180
  and 1180×820, laptop 1440×900, short desktop 1280×720.
- **D15** — this is wave 1; `web/02` and `web/03` build on it.

The slice's own text decides: fluid-first across 320–1440px, at most three
breakpoints, dynamic viewport units RESERVED for the chat composer (`web/04`,
not here), and one layout function shared by the signed-out and signed-in
shells. This RFC decides only the two things the slice left to design — the CSS
organisation and the navigation markup — plus the policy content of the CSP.

## Detailed Design

### 1. One served stylesheet, organised in named layers

`site.css` stays ONE file at one URL. It is reorganised internally into six
labelled sections in cascade order:

    1. tokens      — :root custom properties, including the new layout scale
    2. reset       — box-sizing, body, media defaults
    3. layout      — the page grid, the measure, the fluid gutter
    4. chrome      — .site-header, .site-nav, .site-footer
    5. components  — report, guide, and everything later slices add
    6. breakpoints — the two media blocks, last and together

**Why one file, not several.** Splitting into files needs a concatenation step,
and the build rail that would own it is `web/03/the-typescript-rail`, which
depends on this slice. Shipping a second stylesheet now means either a second
blocking request on every page or a hand-maintained import list that `web/03`
must immediately replace. A file with labelled sections costs nothing and is
trivially split later, once there is a builder to split it with.

**The measure is a variable, not a constant.** `main`'s width becomes

    main {
      width: 100%;
      max-width: var(--measure);
      margin: 0 auto;
      padding: var(--gutter-y) var(--gutter-x);
    }

with `--gutter-x: clamp(1rem, 4vw, 2rem)`. Prose pages keep today's ~760px
measure (`48rem` = 768px). A page that needs the full width — the chat, the
college list — sets `--measure: 80rem` through a body class, which is why the
value is a custom property and not a literal. No layout container carries a
fixed pixel width; that is the property the tests pin.

**Two breakpoints, not three.** `640px` already exists and keeps its job (the
report table stacks below it). One is added at `1024px`, where the gutter and
the type scale step up for laptop and desktop. Between and outside them the
layout is fluid, so 320px, 412px, 820px and 1440px are all covered by the same
rules rather than by a breakpoint each. Three were permitted; two are what the
content needs, and each one is a place a later slice can get wrong.

**Dynamic viewport units are not used here.** `dvh` is reserved for the chat
composer in `web/04` (slice text). Introducing it now, on pages with no pinned
element, would spend the decision in the wrong slice.

### 2. Navigation: wrapping links, no JavaScript, no checkbox

`siteHeader` keeps its `site-header` / `site-nav` class names — eight existing
tests assert them and they are the right names — and changes from a flex row
that overflows at 375px to a wrapping flex container:

    .site-header { flex-wrap: wrap; row-gap: 0.5rem; }
    .site-nav    { display: flex; flex-wrap: wrap; column-gap: 1.25rem; }

The nav's link margins become gaps, so a wrapped second row is spaced like the
first instead of being indented by a stale `margin-left`.

**Why not a hamburger.** A disclosure menu needs either JavaScript — which this
tier does not have until `web/03` and must degrade without in any case — or the
checkbox hack, which is a control that lies about what it is to a screen reader.
With four links today and five after `web/02` adds "Sign in", wrapping is not a
compromise: at 375px the nav takes one extra line, and every destination stays
visible and tappable. A menu that hides four links behind a button is a worse
answer that merely looks more like an app.

Tap targets get `min-height: 44px` and vertical padding — on the nav links and
on the footer links, which are tapped on a phone too — so the rows are
thumb-sized rather than merely present.

**One layout function, extended not forked.** `siteLayout` gains one optional
parameter, `width: ShellWidth = ShellWidth.PROSE`, whose member's `bodyClass` is
written onto `<body>`. That is the seam `web/02`'s signed-in shell uses to
select the wide measure — without a second layout function, which the slice
forbids. It is an ENUM, not a class string: a misspelt class compiles, renders
and selects no rule at all, so the page comes out the wrong width with every
test green. `PROSE` carries a null class (absence, not an empty attribute), so
every existing page renders the same markup it does today apart from the CSS
that styles it; `WIDE` carries `wide-measure`, and a test walks the enum to
prove each member's class is declared by the served stylesheet.

### 3. A content-security policy, set once for the whole app

A new `Route.setContentSecurityPolicy(policy: String = CONTENT_SECURITY_POLICY)`
— sibling of the existing `setLinkHolderPrivacyHeaders` in `web-common`'s `http`
package — installed at the ROOT of public-web's routing, so it covers rendered
pages, the static mount, and the `StatusPages` 404/503 alike. The seam takes the
policy and `web-common` holds the shared default, so a second web tier adopts
the mechanism by calling it rather than by copying the plugin; public-web's call
site is `setContentSecurityPolicy()`.

Policy, as a single header value:

    default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;
    font-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none';
    base-uri 'self'; object-src 'none'

Every directive is chosen against what the tree actually serves today, verified
by grep: no inline `<script>`, no inline `style=`, no external origin, one form
(`POST /verify-email`, same origin). So `'unsafe-inline'` appears nowhere, and
**that is the point of doing this now**: the first inline script anyone writes
fails visibly in the browser instead of quietly becoming a habit that a later
policy can no longer remove. `frame-ancestors 'none'` also gives public-web the
clickjacking protection it has never had.

`connect-src 'self'` is what `web/04`'s streaming fetch needs, and it is already
correct because D2 puts the browser on one origin.

This is ENFORCING, not report-only. Report-only would be the safe choice for a
policy retro-fitted to a large site; here the site is four pages and the policy
is provably satisfied by them.

### 4. What this slice deliberately does not do

- No new route, no new page, no content change.
- No service call, no business rule (D13 — there is nothing for the web tier to
  get wrong here, and that stays true).
- No session, no form, so D11 and D12 have nothing to bind in this slice.
- No dark mode, no theme switch. `profile/appearance` parity is `web/10`.

## Files Modified

- `public-web/src/main/resources/static/site.css` — reorganised into six named
  sections; fluid layout, custom-property measure, wrapping nav, tap targets,
  the second breakpoint at 1024px. The existing 640px report rules are moved
  into the breakpoints section unchanged.
- `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt` — the
  `ShellWidth` enum and the optional `width` parameter on `siteLayout`; nav
  margin classes dropped in favour of CSS gaps. Class names unchanged.
- `web-common/src/main/kotlin/ed/unicoach/web/common/http/ContentSecurityPolicy.kt`
  — NEW. `setContentSecurityPolicy()` and the policy constant, documented as the
  one place the policy is written.
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt` — install the CSP at
  the routing root, above the existing routes.
- `public-web/src/test/kotlin/ed/unicoach/web/ShellMechanismTest.kt` — NEW. The
  mechanism assertions (below).
- `web-common/src/test/kotlin/.../ContentSecurityPolicyTest.kt` — NEW, if
  `web-common` has a test source set; otherwise the header assertions live in
  the public-web test above. The implementer checks and says which.
- `product/0013-unicoach-on-the-web/spec.md` — NOT touched by this run. The
  device-matrix checklist result is reported in the run report, and the ledger
  line is written by /slice's close-out.

## Implementation Plan

1. Rewrite `site.css` section by section, keeping every existing selector's
   rendered effect. The report and guide component rules move but do not change.
2. Add `ShellWidth`/`width` to `siteLayout`; confirm every existing page still
   renders `class="site-header"` and `class="site-footer"`.
3. Add the CSP seam in `web-common` and install it at public-web's routing root.
4. Add the mechanism tests.
5. Run `nix develop -c bin/test`. Existing public-web tests (~115 `@Test`
   methods across the module) must pass unchanged — none of them assert CSS.
6. Manual device-matrix pass (D19) against a locally running `public-web`, on
   the six sizes, for home, terms, privacy, who-pays, verify-email and the cost
   report. Recorded as a checklist in the run report, stated as a HUMAN check.

## Tests

Mechanism tests only, and named as such. D9 is explicit that a test must not
claim to prove rendered responsiveness, so each assertion below pins something a
served byte can actually answer:

1. **Viewport meta present** on a rendered page
   (`width=device-width,
   initial-scale=1`). Already true; pinned so it cannot
   be lost.
2. **The served stylesheet declares both breakpoints** — `/site.css` contains a
   `640px` and a `1024px` media query. Fetched through the static mount, so the
   test reads what a browser reads, not a file on disk.
3. **No fixed pixel width on a layout container, directly OR through a token** —
   the `main`, `.site-header`, `.site-nav` and `.site-footer` rules declare no
   `width:` or `max-width:` in `px`, AND no layout token they read (`--measure`,
   `--gutter-x`, `--gutter-y`) declares a pixel value, wherever it is declared.
   The second half is not decoration: this slice moved the width INTO a token,
   so a guard that watched only the direct properties would pass a stylesheet
   that had silently gone fixed again. Proven by injecting `--measure: 760px`
   and watching it fail.
4. **The measure is a custom property** — `--measure` is declared and `main`
   consumes it, so a later slice widens a page without editing `main`.
5. **CSP header present on a rendered page, on a static asset, and on the 404**
   — three separate assertions, because the third is served by `StatusPages` and
   is exactly where a route-scoped install would have missed.
6. **The policy contains no `'unsafe-inline'` and no `*`** — stated as a
   property of the constant, so weakening it is a deliberate edit to a test and
   not a quiet addition to a string.
7. **No inline `<script>` or `style=` attribute in any rendered page** — a
   source-level sweep over the render package, so a page that would be broken by
   its own policy fails the build rather than the browser.

What is NOT claimed by any test: that the site looks right at 375px. That is the
D19 device matrix, run by a human, reported by name.
