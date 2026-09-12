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
