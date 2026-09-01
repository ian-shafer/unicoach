package ed.unicoach.common.config

import java.net.URI

/**
 * The ONE trailing-slash rule for a public-web link base (RFC 155 D-J).
 *
 * `service.conf` states the public-web origin once (`publicWeb.urlBase`) and
 * appends each page's path to it, so an operator who writes the origin the way a
 * browser prints it — `https://app.uni.coach/` — would otherwise compose
 * `https://app.uni.coach//report`. A per-link escape hatch
 * (`EMAIL_VERIFICATION_VERIFY_URL_BASE`, `COST_REPORT_SHARE_URL_BASE`) can carry
 * the same trailing slash directly.
 *
 * Rather than teach each reader its own defence, the rule lives here and is
 * applied once by each typed config reader to the base it read:
 *
 * - surrounding whitespace is dropped,
 * - every run of slashes INSIDE THE PATH collapses to one,
 * - a trailing path slash is dropped.
 *
 * The parse is [java.net.URI]'s, not this file's. A hand-rolled split on the
 * first `://` collapsed the slashes of a `https://` sitting inside a QUERY, and
 * demoted a scheme-relative base (`//app.uni.coach`) to a path — a host silently
 * turned into a path segment. Only the PATH component is normalized here;
 * scheme, authority, query and fragment are carried through by the platform's
 * own parser and are returned byte-identical.
 *
 * A base that is not a URL at all throws [java.net.URISyntaxException], which
 * both typed readers already fold into `Result.failure`: an unparseable
 * configured origin failing at boot is the honest outcome, not a link composed
 * out of it.
 *
 * The result never ends in `/`, so appending `"/report"` or `"?token=…"`
 * composes exactly one separator.
 */
fun normalizeUrlBase(raw: String): String {
  val parsed = URI(raw.trim())
  val path =
    parsed.path
      .orEmpty()
      .replace(REPEATED_SLASHES, "/")
      .trimEnd('/')
  return URI(parsed.scheme, parsed.authority, path, parsed.query, parsed.fragment).toString()
}

private val REPEATED_SLASHES = Regex("/{2,}")

/**
 * The query key EVERY tokenized public-web link carries its raw token in.
 *
 * Half of a contract the compiler cannot see: `:service` writes the link into a
 * parent's text thread and `public-web` reads the token back out of it, and a
 * link already sent cannot be updated. Renaming either side alone 404s every
 * live link, silently — so both sides read this one name.
 */
const val TOKEN_QUERY_PARAM = "token"

/**
 * A tokenized public-web link: a normalized base plus the raw token.
 *
 * Composed HERE, in the module both sides already depend on, so the minting side
 * and the serving side cannot spell the key differently. [urlBase] is expected to
 * have been through [normalizeUrlBase] already, which is what guarantees exactly
 * one separator before the query.
 */
fun tokenLink(
  urlBase: String,
  rawToken: String,
): String = "$urlBase?$TOKEN_QUERY_PARAM=$rawToken"
