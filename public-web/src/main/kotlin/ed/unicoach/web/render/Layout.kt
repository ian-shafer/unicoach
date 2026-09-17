package ed.unicoach.web.render

import ed.unicoach.web.assets.WebAssets
import ed.unicoach.web.auth.ACCOUNT_PATH
import ed.unicoach.web.auth.SIGN_IN_PATH
import ed.unicoach.web.auth.SIGN_OUT_PATH
import ed.unicoach.web.common.http.ROBOTS_NOINDEX
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.MAIN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.footer
import kotlinx.html.form
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
 * The class the sign-out FORM carries, so the CSS that strips the button's
 * chrome finds it. Named because the markup half and the stylesheet half are
 * one contract, and `ShellMechanismTest` reads the constant rather than retyping
 * the value.
 */
const val SIGN_OUT_FORM_CLASS = "sign-out"

/** A submit button styled as a nav link. Sign-out is a POST, so it cannot be an anchor. */
const val LINKLIKE_CLASS = "linklike"

/**
 * Who the navigation is being rendered FOR (RFC 224).
 *
 * An enum for the same reason [ShellWidth] is one: the two headers differ in
 * which controls they carry, and a caller that passed a free-form string or a
 * boolean pair could ask for a header that is neither. It also keeps the door
 * in ONE place — a "Sign in" link written per page would go missing from the
 * page that forgot it, and the slice's whole door is that link.
 *
 * [SignedOut] is the DEFAULT of [siteLayout]'s `nav` parameter, so no page that
 * existed before RFC 224 changes its call site, and every one of them renders
 * the same body it did; what they gain is the shared header's one new anchor,
 * which is the point of putting the door in the shell rather than on a page.
 */

enum class ShellNav {
  /** No session: the nav carries the door, `<a href="/sign-in">Sign in</a>`. */
  SignedOut,

  /**
   * A live session: the nav carries "Account" and the sign-out control.
   *
   * Sign-out is a `form` with a submit button and NOT an anchor, and that is
   * not styling pedantry: it changes state, so it must be a `POST` the
   * same-origin guard can see (D-C), and a `GET` link would be followed by
   * every prefetcher and link scanner that touches the page.
   */
  SignedIn,
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
  nav: ShellNav = ShellNav.SignedOut,
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
    siteHeader(nav)
    main { content() }
    siteFooter()
  }
}

/**
 * The shared top-level header chrome rendered on every page.
 *
 * The four standing links are the same for everyone; only the tail differs, and
 * it differs by [shellNav] alone. There is no second header function, for the
 * same reason there is no second layout function: two of them drift, and the
 * one that drifts is always the one fewer people look at.
 */
fun FlowContent.siteHeader(shellNav: ShellNav = ShellNav.SignedOut) {
  header("site-header") {
    a(href = "/", classes = "site-brand") { +"unicoach" }
    nav("site-nav") {
      a(href = "/") { +"Home" }
      a(href = "/terms") { +"Terms" }
      a(href = "/privacy") { +"Privacy" }
      // Label and path both read from the page itself, so the header cannot
      // name it one thing while the page calls itself another.
      a(href = WHO_PAYS_PATH) { +whoPaysPage.title }
      when (shellNav) {
        // THE DOOR (RFC 224): the one way in, on every page of the site. The
        // label reads the sign-in page's own name, so the door cannot call the
        // page something other than what the page calls itself.
        ShellNav.SignedOut -> {
          a(href = SIGN_IN_PATH) { +SIGN_IN_HEADING }
        }

        ShellNav.SignedIn -> {
          a(href = ACCOUNT_PATH) { +"Account" }
          form(action = SIGN_OUT_PATH, method = FormMethod.post, classes = SIGN_OUT_FORM_CLASS) {
            button(type = ButtonType.submit, classes = LINKLIKE_CLASS) { +"Sign out" }
          }
        }
      }
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
