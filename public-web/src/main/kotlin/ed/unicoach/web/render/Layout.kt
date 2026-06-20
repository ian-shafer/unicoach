package ed.unicoach.web.render

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
import kotlinx.html.span
import kotlinx.html.title

/**
 * The shared HTML skeleton for every public-web page. Links the chrome-less
 * `/site.css` stylesheet from the static mount and wraps a page body — injected
 * into the `content` slot — in the identical top-level site header and footer.
 * Home, legal, and error pages all render through this one `Layout`, so they
 * inherit the same chrome. No page is authored as an HTML-fragment file; every
 * body is a `kotlinx.html` DSL block passed here.
 */
fun HTML.siteLayout(
  pageTitle: String,
  content: MAIN.() -> Unit,
) {
  head {
    meta(charset = "utf-8")
    meta(name = "viewport", content = "width=device-width, initial-scale=1")
    title { +"unicoach — $pageTitle" }
    link(rel = "stylesheet", href = "/site.css")
  }
  body {
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
