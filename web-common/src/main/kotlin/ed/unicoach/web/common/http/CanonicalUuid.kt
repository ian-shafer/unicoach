package ed.unicoach.web.common.http

import java.util.UUID

/**
 * [raw] as a UUID iff it is a canonical one, or null. `UUID.fromString` also
 * accepts short groups (`1-2-3-4-5`), so only a value that prints back as itself
 * is an id. The one rule for every id a route reads, from the path or from a
 * body field — in `rest-server` and in `public-web` alike, which is why it lives
 * here rather than in either (RFC 262).
 */
fun canonicalUuidOrNull(raw: String): UUID? {
  val id =
    try {
      UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
      return null
    }
  return id.takeIf { it.toString() == raw.lowercase() }
}
