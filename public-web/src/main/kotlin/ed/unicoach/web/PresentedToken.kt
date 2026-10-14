package ed.unicoach.web

import ed.unicoach.coaching.report.ShareToken

/**
 * WHAT a request presented on a single-token key: the one usable token, or
 * which miss it was.
 *
 * `queryParameters[name]` is `getAll(name).firstOrNull()`, so a link carrying
 * two tokens is a request we have no reading of rather than silently the first
 * of them. That rule is subtle and security-bearing, and four doors re-derived
 * it from `getAll(...).singleOrNull()`; it has one name here instead. Each door
 * keeps its OWN miss vocabulary, which is the only part that differs. Its own
 * file, because the signed-in landing in `auth/` reads a door's URL by the same
 * rule (RFC 254) and must not disagree with the door about it.
 */
internal sealed interface PresentedToken {
  data class One(
    val raw: String,
  ) : PresentedToken

  /** The key was not present at all: this request presented no token. */
  data object Absent : PresentedToken

  /** The key WAS present and carried nothing usable — a truncated link, not an absent one. */
  data object Blank : PresentedToken

  /** Two or more values on the one key: two credentials, not one. */
  data object Repeated : PresentedToken
}

/**
 * The ONE token [values] carries, or which miss it was. [values] is a `getAll`.
 *
 * Nothing is `null` here on purpose: a nullable `raw` member let a consumer
 * null-check it and then identity-test the object to recover what the type had
 * already said, so a fourth case would be read as the third instead of failing
 * to compile.
 */
internal fun presentedToken(values: List<String>?): PresentedToken {
  if (values == null) return PresentedToken.Absent
  if (values.size > 1) return PresentedToken.Repeated
  val single = values.singleOrNull()
  return if (single.isNullOrBlank()) PresentedToken.Blank else PresentedToken.One(single)
}

/**
 * The token when exactly one usable one was presented, for the doors that want
 * only "usable or not" and answer every miss the same way.
 */
internal fun PresentedToken.usableToken(): String? = (this as? PresentedToken.One)?.raw

/**
 * The report token a signup door carries (RFC 254): ONE value, and only in the
 * shape the server mints ([ShareToken.isWellFormed]). Not "not blank": junk on
 * the `report` key is never echoed into a form or turned into a redirect.
 */
internal fun PresentedToken.usableReportToken(): String? = usableToken()?.takeIf(ShareToken::isWellFormed)
