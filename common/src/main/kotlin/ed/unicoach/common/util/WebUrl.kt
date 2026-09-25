package ed.unicoach.common.util

/**
 * Is this string a url a browser will fetch over the web?
 *
 * ONE QUESTION, ASKED IN ONE PLACE (RFC 196). It is about the `http`/`https`
 * scheme and nothing else -- not about whether the host resolves, whether the
 * document exists, or whether the url is well-formed beyond its scheme. It
 * neither parses nor rewrites: the caller keeps the string it was given.
 *
 * It lives in `:common` because it is domain-agnostic. Three places in this repo
 * put a stored or supplied url into an `href`, and `href` is the one attribute
 * where escaping does not protect a reader -- `javascript:` in an `href` is live
 * whatever an HTML builder does to the attribute value. A predicate re-typed at
 * each of them is a predicate that will disagree with itself; this one is called
 * from `CdsCitation.linkTarget` today.
 *
 * The COMPARISON is case-insensitive and the PREFIX includes `://`:
 *
 * - a scheme is case-insensitive in RFC 3986, so `HTTPS://example.test/a.pdf` is
 *   a url a browser follows and calling it unsafe would be a false alarm -- and
 *   the same looseness catches `JavaScript:`, which a case-sensitive check on a
 *   lowercase deny-list would let through;
 * - `https:` alone, without the slashes, admits `https:alert(1)`, which is not a
 *   web url.
 *
 * WHAT IT IS NOT: an authorisation, a sanitiser and an escaper. A caller decides
 * what a `false` MEANS -- this repo's one caller treats it as a corrupt stored
 * row and faults, because the alternative to refusing is rendering an executable
 * link.
 */
fun isWebUrl(value: String): Boolean = value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

/**
 * Refuses [url] unless it is an address this codebase is willing to CITE,
 * naming [subject] so the refusal says which citation it is about.
 *
 * ONE HOME FOR ONE RULE. Five value types guarded "a page we cite" with their
 * own copy of a scheme test, and three of those copies demanded `https://`
 * alone -- which is not the bar the store keeps. `external_documents` accepts
 * `http` by an explicit decision (its DDL: "http IS ACCEPTED, https
 * PREFERRED ... an https-only CHECK would refuse data we have"), and the
 * landed corpus holds plain-http agency pages and plain-http archived
 * addresses. A stricter bar in a reader does not raise a publisher's standard;
 * it deletes their row from the page, or faults the whole read.
 *
 * WHAT IT IS NOT: a parse. The question is [isWebUrl]'s -- the scheme, and
 * nothing else -- so a caller with a url that must also have a host, resolve or
 * exist asks that separately.
 */
fun requireCitedPageUrl(
  url: String,
  subject: String,
) {
  require(isWebUrl(url)) { "$subject is not an absolute http(s) page, got [$url]" }
}
