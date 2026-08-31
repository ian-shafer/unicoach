package ed.unicoach.db.models

/**
 * One row of the authored subject taxonomy (`subjects`, RFC 150 D49) — the file
 * shape and the write shape at once, the [NewIpedsRegion] convention: the table
 * has no surrogate key and no column a reader is not entitled to, so a separate
 * read type would be the identical three fields under a second name.
 *
 * [slug] is the shared `slug` DOMAIN, so a subject speaks the same dialect as
 * every RFC 147 reference table, and it is the word the tool schema advertises.
 * [cipPrefixes] are 2-, 4- or 6-digit CIP prefixes in the canonical digits-only
 * form `CipPrefix.parseOrNull` produces; the schema constrains only their SHAPE,
 * because a CHECK cannot contain a subquery. Proving that each one matches at
 * least one real `cip_codes` row is the loader's job, and it is FATAL there.
 */
data class NewSubject(
  val slug: String,
  val name: String,
  val cipPrefixes: List<String>,
)
