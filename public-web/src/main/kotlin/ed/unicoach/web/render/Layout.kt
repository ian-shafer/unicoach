package ed.unicoach.web.render

import ed.unicoach.web.assets.WebAssets
import ed.unicoach.web.common.http.ROBOTS_NOINDEX
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.footer
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.title

/**
 * The width a page's column is read at — the whole of the `<body>`-class seam
 * RFC 210 opened, expressed as the CHOICE a page makes rather than as the CSS
 * class that implements it.
 *
 * A caller names a member; the class name is written in exactly one place per
 * member, next to the stylesheet rule it has to agree with. A free-form class
 * string could not offer that: a typo in it is a legal Kotlin expression that
 * renders fine and selects nothing, so the page silently comes out at the
 * default width. A member added here without a matching rule in `site.css`
 * fails `ShellMechanismTest` instead.
 *
 * [bodyClass] is nullable because the default width is the ABSENCE of a class,
 * not a class that happens to do nothing — and `null` is how a null-safe
 * language says absent.
 */
enum class ShellWidth(
  val bodyClass: String?,
) {
  /**
   * The reading measure (~48rem): text a person reads in lines, which is every
   * page public-web serves today. It carries no class because it is what `main`
   * already does, so a page written before RFC 210 renders identical bytes.
   */
  PROSE(null),

  /**
   * The full column (`--measure: 80rem`): content that is not prose and is
   * cramped by the reading measure — `web/02`'s signed-in shell, the chat, the
   * college list. It widens the page by setting the token `main` already reads,
   * so no page-specific width rule is ever written.
   */
  WIDE("wide-measure"),
}

/**
 * The shared HTML skeleton for every public-web page. Links the chrome-less
 * `/site.css` stylesheet from the static mount and wraps a page body — injected
 * into the `content` slot — in the identical top-level site header and footer.
 * Home, legal, and error pages all render through this one `Layout`, so they
 * inherit the same chrome. No page is authored as an HTML-fragment file; every
 * body is a `kotlinx.html` DSL block passed here.
 *
 * [noindex] is the one seam RFC 155 added: it emits a `robots` meta for the
 * tokenized cost report, and defaults to off so every other page renders
 * byte-identically to what it rendered before.
 *
 * [width] is the seam RFC 210 added, and it is why there is still ONE layout
 * function: the signed-in shell selects the wide measure (`--measure`) instead
 * of forking a second layout that would drift from this one. It is an ENUM and
 * not a CSS-class string, because a misspelt class compiles, renders and
 * silently selects no rule at all — the page simply comes out the wrong width,
 * with every test still green. [ShellWidth.PROSE] by default, so every page
 * that existed before renders the same markup it did.
 */
fun HTML.siteLayout(
  pageTitle: String,
  width: ShellWidth = ShellWidth.PROSE,
  noindex: Boolean = false,
  content: MAIN.() -> Unit,
) {
  head {
    meta(charset = "utf-8")
    meta(name = "viewport", content = "width=device-width, initial-scale=1")
    // Off by default, so every page that existed before RFC 155 renders the
    // exact same bytes it did. Only the tokenized report opts in, and the
    // matching `X-Robots-Tag` header rides on the route (RFC 155 D-H) — the
    // meta covers a crawler that reads the body, the header covers one that
    // reads only the response head.
    if (noindex) {
      meta(name = "robots", content = ROBOTS_NOINDEX)
    }
    title { +"unicoach — $pageTitle" }
    link(rel = "stylesheet", href = "/site.css")
    // The one client-side bundle (RFC 225). An EXTERNAL same-origin file, so
    // `script-src 'self'` already allows it and the content-security policy
    // does not change; an inline script would need `'unsafe-inline'`, which is
    // the thing the policy exists to refuse.
    //
    // `src` is read from the manifest and is never a literal, so the tag cannot
    // name a bundle that is not the built one. `type = "module"` implies defer,
    // so no `defer` attribute is written and nothing blocks parsing. EVERY page
    // gets it: every page is a candidate host for a future enhancement, and a
    // per-page opt-in is a second thing to keep in sync.
    script {
      type = "module"
      src = WebAssets.MAIN
    }
  }
  // `bodyClass` is `null` for the default width rather than "": kotlinx.html
  // renders an empty `class=""` attribute for a blank string, and every page
  // that existed before RFC 210 must keep rendering the bytes it did.
  body(classes = width.bodyClass) {
    siteHeader()
    main { content() }
    siteFooter()
  }
}

/** The shared top-level header chrome rendered on every page. */
fun FlowContent.siteHeader() {
  header("site-header") {
    a(href = "/", classes = "site-brand") { +"unicoach" }
    nav("site-nav") {
      a(href = "/") { +"Home" }
      a(href = "/terms") { +"Terms" }
      a(href = "/privacy") { +"Privacy" }
      // Label and path both read from the page itself, so the header cannot
      // name it one thing while the page calls itself another.
      a(href = WHO_PAYS_PATH) { +whoPaysPage.title }
    }
  }
}

/** The shared top-level footer chrome rendered on every page. */
fun FlowContent.siteFooter() {
  footer("site-footer") {
    p {
      span { +"© unicoach" }
      a(href = "/terms") { +"Terms of Service" }
      a(href = "/privacy") { +"Privacy Policy" }
    }
  }
}
