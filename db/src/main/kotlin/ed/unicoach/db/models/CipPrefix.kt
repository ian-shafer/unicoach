package ed.unicoach.db.models

/**
 * Canonicalization for [CollegeQuery.cipPrefix], the one field of a college
 * query an LLM writes as prose rather than as a number.
 *
 * CIP codes are conventionally written dotted -- "51.38", "26.0702" -- and
 * models overwhelmingly emit that form, while [ed.unicoach.db.dao.CollegesDao]
 * binds the prefix into `cip_code LIKE prefix || '%'` against digits-only
 * `cip_code` values. Every boundary that accepts a model-authored prefix
 * therefore has to canonicalize before the value reaches a query, so the rule
 * lives with the field it constrains rather than in one of its callers.
 *
 * The dot is parsed as the family/detail SEPARATOR it actually is, never simply
 * deleted. Deleting it is lossy: "5.0103" (an elided leading zero on the 05
 * family) would become the meaningless "50103", and "5.138" would become
 * "5138" -- a valid prefix for 51.38 Nursing, i.e. a silently wrong answer.
 * Splitting and left-padding the family recovers "5.0103" as "050103" and
 * leaves genuinely ambiguous input to be rejected by the caller.
 */
object CipPrefix {
  /** The canonical stored form: a 2-, 4-, or 6-digit CIP prefix. */
  val CANONICAL_REGEX = Regex("^([0-9]{2}|[0-9]{4}|[0-9]{6})$")

  /** The conventional dotted form: `family.detail`, detail 2 or 4 digits (or absent). */
  private val DOTTED_REGEX = Regex("^([0-9]{1,2})\\.([0-9]{2}|[0-9]{4})?$")

  /**
   * Returns [raw] in canonical digits-only form, or null when it cannot be read
   * unambiguously as a CIP prefix. A null result is a REJECTION, not an absence
   * -- callers must answer it (an error to the model), never forward [raw] into a
   * query, where it would match no program and read as an honest empty result.
   */
  fun parseOrNull(raw: String): String? {
    val trimmed = raw.trim()
    val canonical = if (trimmed.contains('.')) parseDottedPrefix(trimmed) else trimmed
    return canonical?.takeIf { it.matches(CANONICAL_REGEX) }
  }

  /**
   * Joins a dotted prefix into digits, left-padding a family written without its
   * leading zero ("5.0103" -> "050103"). A detail of 2 or 4 digits is the only
   * dotted shape CIP uses; an empty detail ("26.") is a family written with the
   * separator left on. Anything else -- "5.138", two dots, non-digits -- is not
   * a CIP prefix that can be read one way, so it is refused rather than guessed.
   */
  private fun parseDottedPrefix(trimmed: String): String? {
    val match = DOTTED_REGEX.matchEntire(trimmed) ?: return null
    val (family, detail) = match.destructured
    return family.padStart(2, '0') + detail
  }
}
