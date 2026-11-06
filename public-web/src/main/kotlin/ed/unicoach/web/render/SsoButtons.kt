package ed.unicoach.web.render

import ed.unicoach.db.models.AuthProvider
import ed.unicoach.web.auth.SIGN_IN_PATH
import ed.unicoach.web.common.http.LinkHolderReferrerPolicy
import ed.unicoach.web.common.http.SiteOrigin
import ed.unicoach.web.common.http.contentSecurityPolicy
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.hiddenInput
import kotlinx.html.p

/**
 * One provider's web sign-in door (RFC 275): the path its token is posted to,
 * the provider `:service` verifies it as, the name the page's sentences call
 * it by, and the provider script its button needs.
 *
 * One enum rather than two parallel `when`s, so the route a form posts to and
 * the route that reads it cannot name two different providers. The script URL
 * is a fact of the door for the same reason: a page head loads doors, not
 * URLs, so no page can load a third-party script these doors do not name.
 */
enum class SsoDoor(
  val provider: AuthProvider,
  val label: String,
  val segment: String,
  val sdkUrl: String,
) {
  GOOGLE(AuthProvider.GOOGLE, "Google", "google", GOOGLE_SDK_URL),
  APPLE(AuthProvider.APPLE, "Apple", "apple", APPLE_SDK_URL),
  ;

  /** The same-origin path the page's hidden form posts the provider's ID token to. */
  val path: String get() = "$SIGN_IN_PATH/$segment"
}

/** The posted field carrying the provider's ID token, as the provider issued it. */
const val ID_TOKEN_FIELD = "id_token"

/** The Google button's host: the provider script fills it with its own iframe. */
const val SSO_GOOGLE_ATTRIBUTE = "data-sso-google"

/** Our Apple button: the bundle runs Apple's popup from its click. */
const val SSO_APPLE_ATTRIBUTE = "data-sso-apple"

/** Marks each hidden form the bundle fills and submits, by its door's [SsoDoor.segment]. */
const val SSO_FORM_ATTRIBUTE = "data-sso-form"

/** The client ID the bundle hands the provider, on its host element. */
const val SSO_CLIENT_ID_ATTRIBUTE = "data-client-id"

/** The return URL Apple requires even in popup mode, on the Apple button. */
const val SSO_REDIRECT_URI_ATTRIBUTE = "data-redirect-uri"

/** The line the bundle writes its sentences into. */
const val SSO_STATUS_ATTRIBUTE = "data-sso-status"

/** The block's own class: the buttons' column and spacing. */
const val SSO_CLASS = "sso"

/** Our Apple button's class, styled to Apple's guidelines. */
const val SSO_APPLE_BUTTON_CLASS = "sso-apple"

/** The status line's class. */
const val SSO_STATUS_CLASS = "sso-status"

/** The divider's class, between the buttons and the email form. */
const val SSO_DIVIDER_CLASS = "sso-divider"

/** Our Apple button's label (D-F). */
const val CONTINUE_WITH_APPLE = "Continue with Apple"

/** The divider (D-F). */
const val SSO_DIVIDER = "or use your email"

/**
 * Google's Identity Services script, and the sources it needs (Google's
 * documented set, plus inline style). Stated beside each other because the page loads the script
 * and the policy admits it: one of them changed alone is a button the browser
 * blocks.
 */
const val GOOGLE_SDK_URL = "https://accounts.google.com/gsi/client"

private val GOOGLE_SOURCES =
  mapOf(
    "script-src" to listOf(GOOGLE_SDK_URL),
    // `frame-src` is new to the policy: the button is an iframe at this path.
    "frame-src" to listOf("https://accounts.google.com/gsi/"),
    "connect-src" to listOf("https://accounts.google.com/gsi/"),
    // `'unsafe-inline'` because GIS `renderButton` writes a `<style>` element
    // and `style=` attributes into OUR page, outside its iframe, and the
    // documented `gsi/style` source admits neither: under a strict `style-src`
    // the button renders as a bare "G" with its label twice (RFC 275 §6, Ian's
    // call mid-run). Scoped to these pages, only when Google is configured;
    // `script-src` stays strict, and `img-src`/`font-src 'self'` leave an
    // injected style no off-site URL to load.
    "style-src" to listOf("https://accounts.google.com/gsi/style", "'unsafe-inline'"),
  )

/** Apple's Sign in with Apple JS, and its one source: the popup is a window, not a frame. */
const val APPLE_SDK_URL = "https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js"

private val APPLE_SOURCES = mapOf("script-src" to listOf("https://appleid.cdn-apple.com"))

/** Google's half of the block: the Web OAuth client the button is for. */
data class GoogleSignIn(
  val clientId: String,
)

/** Apple's half: the Services ID, and the return URL registered on it (derived, never configured). */
data class AppleSignIn(
  val servicesId: String,
  val redirectUri: String,
)

/**
 * Which provider buttons the sign-in and Create-account pages render (RFC 275
 * D-C), and the policy those pages need for them.
 *
 * A provider is present exactly when its web ID is configured, and everything
 * the page carries for it — the button, the script, the widened policy — is
 * derived from that one presence. [NONE] renders today's pages byte for byte.
 */
data class SsoButtons(
  val google: GoogleSignIn?,
  val apple: AppleSignIn?,
) {
  /** Is there anything to render at all? */
  val anyConfigured: Boolean get() = google != null || apple != null

  /** The configured doors whose provider scripts the page head loads, in a stable order. */
  val scripts: List<SsoDoor>
    get() = listOfNotNull(google?.let { SsoDoor.GOOGLE }, apple?.let { SsoDoor.APPLE })

  /**
   * The policy the two pages carry: the default plus each configured
   * provider's sources and nothing else (RFC 275 §6). With neither configured
   * it IS the default, so a route need not ask whether to override.
   */
  val contentSecurityPolicy: String
    get() {
      val extra = listOfNotNull(google?.let { GOOGLE_SOURCES }, apple?.let { APPLE_SOURCES })
      return contentSecurityPolicy(
        extra.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, lists) -> lists.flatten() },
      )
    }

  /**
   * The pages' `Referrer-Policy`. Google's button is a cross-origin frame that
   * reads the embedding site's ORIGIN, which `same-origin` withholds; the wider
   * value still never sends the path or query, where an invite or report
   * token rides. Apple's popup needs no `Referer`, so Apple alone keeps
   * `same-origin`.
   */
  val referrerPolicy: LinkHolderReferrerPolicy
    get() = if (google != null) LinkHolderReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN else LinkHolderReferrerPolicy.SAME_ORIGIN

  companion object {
    /** No provider configured: today's pages. */
    val NONE = SsoButtons(google = null, apple = null)

    /**
     * The buttons for the configured web IDs. Apple's return URL is DERIVED
     * from the site's own [origin] (`PUBLIC_WEB_URL_BASE`) plus the sign-in
     * path, so it cannot disagree with the page that serves the button.
     */
    fun of(
      googleClientId: String?,
      appleServicesId: String?,
      origin: SiteOrigin,
    ): SsoButtons =
      SsoButtons(
        google = googleClientId?.let(::GoogleSignIn),
        apple = appleServicesId?.let { AppleSignIn(it, "${origin.url}$SIGN_IN_PATH") },
      )
  }
}

/**
 * The SSO block, above the email form on the sign-in and Create-account pages.
 *
 * [refusal] is the sentence a refused provider post re-renders with (§4). It
 * renders even when no button does, because a post can arrive at a door this
 * page has no button for, and its answer must still be on the page. With no
 * provider and no refusal the block renders nothing at all.
 *
 * [appleName] is the name a refused Apple post carried, put back in the Apple
 * form so the retry — whose token carries no name — sends it again. Ordinary
 * form handling on a signed-out page, as the email field's re-render is.
 */
fun FlowContent.ssoBlock(
  buttons: SsoButtons,
  token: String?,
  report: String?,
  refusal: String? = null,
  appleName: String? = null,
) {
  formError(refusal)
  if (!buttons.anyConfigured) return
  div(SSO_CLASS) {
    buttons.google?.let { googleSignIn(it, token, report) }
    buttons.apple?.let { appleSignIn(it, token, report, appleName) }
    ssoStatusLine()
  }
  p(SSO_DIVIDER_CLASS) { +SSO_DIVIDER }
}

/** Google's button host and the form its token is submitted through. */
private fun FlowContent.googleSignIn(
  google: GoogleSignIn,
  token: String?,
  report: String?,
) {
  // The provider script fills this host with its own button.
  div {
    attributes[SSO_GOOGLE_ATTRIBUTE] = ""
    attributes[SSO_CLIENT_ID_ATTRIBUTE] = google.clientId
  }
  ssoForm(SsoDoor.GOOGLE, token, report)
}

/** Apple's button and the form its token, and the name it sent, are submitted through. */
private fun FlowContent.appleSignIn(
  apple: AppleSignIn,
  token: String?,
  report: String?,
  appleName: String?,
) {
  // OURS, not the SDK-rendered `#appleid-signin`: the SDK's markup is not
  // something the enforcing `style-src 'self'` has been shown to admit.
  button(type = ButtonType.button, classes = SSO_APPLE_BUTTON_CLASS) {
    attributes[SSO_APPLE_ATTRIBUTE] = ""
    attributes[SSO_CLIENT_ID_ATTRIBUTE] = apple.servicesId
    attributes[SSO_REDIRECT_URI_ATTRIBUTE] = apple.redirectUri
    +CONTINUE_WITH_APPLE
  }
  ssoForm(SsoDoor.APPLE, token, report) {
    hiddenInput(name = NAME_FIELD) { value = appleName.orEmpty() }
  }
}

/** The line the bundle writes a provider's cancelled or could-not-load sentence into. */
private fun FlowContent.ssoStatusLine() {
  p(SSO_STATUS_CLASS) {
    attributes[SSO_STATUS_ATTRIBUTE] = ""
    attributes["role"] = "status"
    attributes["aria-live"] = "polite"
  }
}

/**
 * The hidden form the bundle fills with [door]'s token and submits, carrying
 * the page's invite and report tokens like the email form beside it (RFC 232,
 * RFC 254).
 */
private fun FlowContent.ssoForm(
  door: SsoDoor,
  token: String?,
  report: String?,
  extra: FlowContent.() -> Unit = {},
) {
  form(action = door.path, method = FormMethod.post) {
    attributes[SSO_FORM_ATTRIBUTE] = door.segment
    hiddenInput(name = ID_TOKEN_FIELD)
    tokenField(token)
    reportField(report)
    extra()
  }
}
